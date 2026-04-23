package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.constant.LobbyStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for LobbyService.
 *
 * Every dependency is mocked: no DB, no real STOMP broker, no real
 * RoundService. These tests exercise the business rules the service is
 * responsible for:
 *   - Input validation (rounds / maxPlayers bounds, host exists)
 *   - Unique lobby code generation (format + retry-on-collision)
 *   - WS broadcasts on join / start / host-disconnect
 *   - The host-disconnect cleanup chain (rounds cleanup + join table
 *     clear + lobby delete) that unblocks the subsequent user delete
 *   - The start-game reorder that broadcasts /start before kicking off
 *     the Mapillary-heavy round bootstrap
 *
 * The class is grouped by method-under-test and tagged with the US /
 * ticket numbers from the construction-sheet task list so each dev task
 * has at least one associated test.
 *
 * This file will grow as US3-US5 tests are added in subsequent passes.
 */
public class LobbyServiceTest {

    @Mock
    private LobbyRepository lobbyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RoundService roundService;

    @InjectMocks
    private LobbyService lobbyService;

    private User host;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        host = new User();
        host.setId(1L);
        host.setUsername("host");
    }

    // -------------------------------------------------------------------
    // createLobby — US2 (#62, #63, #64)
    // -------------------------------------------------------------------

    /**
     * US2 #64 — Store lobby configuration.
     *
     * A valid createLobby call must persist a Lobby with:
     *   - The supplied maxPlayers and totalRounds
     *   - The supplied host id
     *   - status = WAITING (lobbies always start open for joining)
     *   - The host added as the first entry of the players list
     *   - A generated lobbyCode (format verified separately)
     *
     * This is the happy-path positive control for the whole method. If
     * any of these invariants regress, the frontend waiting room will
     * display stale or missing info.
     */
    @Test
    public void createLobby_validInput_persistsAllConfigAndAddsHostToPlayers() {
        // given
        Lobby input = new Lobby();
        input.setHostUserId(1L);
        input.setMaxPlayers(4);
        input.setTotalRounds(3);

        given(userRepository.findById(1L)).willReturn(Optional.of(host));
        given(lobbyRepository.findByLobbyCode(anyString())).willReturn(null); // no collisions
        given(lobbyRepository.save(any(Lobby.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        Lobby result = lobbyService.createLobby(input);

        // then — every config field is carried through, plus the service-set defaults
        assertEquals(4, result.getMaxPlayers(), "maxPlayers must be persisted");
        assertEquals(3, result.getTotalRounds(), "totalRounds must be persisted");
        assertEquals(1L, result.getHostUserId(), "hostUserId must be persisted");
        assertEquals(LobbyStatus.WAITING, result.getStatus(), "new lobbies must start in WAITING");
        assertNotNull(result.getLobbyCode(), "a lobbyCode must be generated");
        assertTrue(result.getPlayers().contains(host), "host must be added to the players list");

        // verify save + flush happened
        verify(lobbyRepository).save(any(Lobby.class));
        verify(lobbyRepository).flush();
    }

    /**
     * US2 #63 — Unique lobby code generation.
     *
     * The generated code must follow the pattern [A-Z][A-Z]-[0-9]{4}
     * (two uppercase letters, dash, four digits). This is the pattern
     * the frontend's "join via code" form expects users to type. If a
     * future refactor changes the alphabet or the digit count, the
     * join flow would silently break.
     */
    @Test
    public void createLobby_generatedCodeMatchesExpectedFormat() {
        // given
        Lobby input = new Lobby();
        input.setHostUserId(1L);
        input.setMaxPlayers(4);
        input.setTotalRounds(3);

        given(userRepository.findById(1L)).willReturn(Optional.of(host));
        given(lobbyRepository.findByLobbyCode(anyString())).willReturn(null);
        given(lobbyRepository.save(any(Lobby.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        Lobby result = lobbyService.createLobby(input);

        // then
        assertTrue(result.getLobbyCode().matches("^[A-Z]{2}-\\d{4}$"),
                "Expected lobbyCode format AA-1234 but got: " + result.getLobbyCode());
    }

    /**
     * US2 #63 — Unique lobby code generation (retry-on-collision branch).
     *
     * generateLobbyCode loops while findByLobbyCode(candidate) returns
     * non-null. This test simulates a hash collision on the first
     * attempt and verifies the service regenerates a fresh code and
     * queries again. Without this branch, a second lobby with the same
     * code could slip past and violate the unique constraint at
     * flush-time, returning a generic DB error to the user.
     */
    @Test
    public void createLobby_codeCollision_regeneratesUntilFree() {
        // given — first findByLobbyCode call returns an occupying lobby, second returns null
        Lobby occupying = new Lobby();
        occupying.setLobbyCode("ZZ-9999");

        Lobby input = new Lobby();
        input.setHostUserId(1L);
        input.setMaxPlayers(4);
        input.setTotalRounds(3);

        given(userRepository.findById(1L)).willReturn(Optional.of(host));
        given(lobbyRepository.findByLobbyCode(anyString()))
                .willReturn(occupying)   // attempt 1: collision
                .willReturn(null);       // attempt 2: free
        given(lobbyRepository.save(any(Lobby.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        lobbyService.createLobby(input);

        // then — findByLobbyCode was called exactly twice (one collision + one success)
        verify(lobbyRepository, times(2)).findByLobbyCode(anyString());
    }

    /**
     * US2 #64 — Config validation: rounds out of range (below min).
     *
     * Rounds must be in [1, 10]. Below 1 -> 400 BAD_REQUEST. We test
     * the lower bound explicitly because the create-lobby form slider
     * is clamped client-side, and the server must still enforce the
     * contract against malicious or buggy clients.
     */
    @Test
    public void createLobby_roundsBelowMin_throws400() {
        Lobby input = new Lobby();
        input.setHostUserId(1L);
        input.setMaxPlayers(4);
        input.setTotalRounds(0); // below minimum

        given(userRepository.findById(1L)).willReturn(Optional.of(host));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.createLobby(input));
        assertEquals(400, ex.getStatusCode().value());
    }

    /**
     * US2 #64 — Config validation: rounds out of range (above max).
     */
    @Test
    public void createLobby_roundsAboveMax_throws400() {
        Lobby input = new Lobby();
        input.setHostUserId(1L);
        input.setMaxPlayers(4);
        input.setTotalRounds(11); // above maximum

        given(userRepository.findById(1L)).willReturn(Optional.of(host));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.createLobby(input));
        assertEquals(400, ex.getStatusCode().value());
    }

    /**
     * US2 #64 — Config validation: maxPlayers out of range (below min).
     *
     * A lobby needs at least 2 players to be playable. Setting maxPlayers
     * to 1 would create a lobby that can never reach the "start game"
     * threshold. 400 BAD_REQUEST prevents this from getting persisted.
     */
    @Test
    public void createLobby_maxPlayersBelowMin_throws400() {
        Lobby input = new Lobby();
        input.setHostUserId(1L);
        input.setMaxPlayers(1); // below minimum
        input.setTotalRounds(3);

        given(userRepository.findById(1L)).willReturn(Optional.of(host));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.createLobby(input));
        assertEquals(400, ex.getStatusCode().value());
    }

    /**
     * US2 #64 — Config validation: maxPlayers out of range (above max).
     */
    @Test
    public void createLobby_maxPlayersAboveMax_throws400() {
        Lobby input = new Lobby();
        input.setHostUserId(1L);
        input.setMaxPlayers(11); // above maximum
        input.setTotalRounds(3);

        given(userRepository.findById(1L)).willReturn(Optional.of(host));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.createLobby(input));
        assertEquals(400, ex.getStatusCode().value());
    }

    /**
     * US2 (supporting) — Unknown host rejected with 404.
     *
     * If the request carries a hostUserId that doesn't exist (stale
     * session, tampered request), createLobby must fail cleanly with
     * 404 NOT_FOUND rather than persisting an orphan lobby with a
     * dangling host reference.
     */
    @Test
    public void createLobby_unknownHost_throws404() {
        Lobby input = new Lobby();
        input.setHostUserId(999L);
        input.setMaxPlayers(4);
        input.setTotalRounds(3);

        given(userRepository.findById(999L)).willReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.createLobby(input));
        assertEquals(404, ex.getStatusCode().value());
    }

    /**
     * US2 #64 — Defensive: the same argument we passed in is saved
     * (identity check via ArgumentCaptor).
     *
     * The service must save THE lobby it was given (mutated with code,
     * status, host), not a brand-new instance. If a future refactor
     * introduces a copy step that drops a field, this test catches it
     * by verifying the captured save argument retains the supplied
     * maxPlayers + totalRounds.
     */
    @Test
    public void createLobby_savesExactMutatedEntity() {
        Lobby input = new Lobby();
        input.setHostUserId(1L);
        input.setMaxPlayers(6);
        input.setTotalRounds(5);

        given(userRepository.findById(1L)).willReturn(Optional.of(host));
        given(lobbyRepository.findByLobbyCode(anyString())).willReturn(null);
        given(lobbyRepository.save(any(Lobby.class))).willAnswer(inv -> inv.getArgument(0));

        lobbyService.createLobby(input);

        ArgumentCaptor<Lobby> captor = ArgumentCaptor.forClass(Lobby.class);
        verify(lobbyRepository).save(captor.capture());
        Lobby saved = captor.getValue();
        assertEquals(6, saved.getMaxPlayers());
        assertEquals(5, saved.getTotalRounds());
        assertEquals(LobbyStatus.WAITING, saved.getStatus());
        assertNotNull(saved.getLobbyCode());
        assertTrue(saved.getPlayers().contains(host));
    }

    // -------------------------------------------------------------------
    // joinLobby — US3 (#71) + US4 (#74, #75, #76)
    // -------------------------------------------------------------------

    /**
     * US3 #71 — Block joining a lobby that has already started.
     *
     * Once the host clicks Start, the lobby flips to INGAME and further
     * joins must be rejected with 403 FORBIDDEN. Otherwise a late-joining
     * player would land on the waiting room while everyone else is in
     * the game — no way to catch up to the image sequence that's already
     * in progress.
     *
     * The check happens before the capacity / already-in-lobby checks,
     * so we don't even need to stub the user repository — the test
     * never reaches that line.
     */
    @Test
    public void joinLobby_lobbyAlreadyStarted_throws403() {
        // given — a lobby whose game has already started
        Lobby inGame = new Lobby();
        inGame.setLobbyCode("AB-1234");
        inGame.setStatus(LobbyStatus.INGAME);
        inGame.setMaxPlayers(4);
        inGame.getPlayers().add(host);
        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(inGame);

        // when / then — join must fail with 403 FORBIDDEN
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.joinLobby("AB-1234", 2L));
        assertEquals(403, ex.getStatusCode().value());

        // and no side effects happened (no save, no broadcast)
        verify(lobbyRepository, never()).save(any(Lobby.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * US4 #74 / #76 — Happy path: join a lobby, save, broadcast roster.
     *
     * When a guest POSTs to /lobbies/{code}/players with their user id,
     * the service must:
     *   1. Look up the lobby by code.
     *   2. Confirm status is WAITING, lobby has capacity, user exists,
     *      and user isn't already in the lobby (those branches each
     *      have their own negative tests below).
     *   3. Append the user to lobby.players.
     *   4. Persist + flush the updated lobby (this writes the new row
     *      to the lobby_players join table).
     *   5. Broadcast the full current roster to
     *      /topic/lobby/{code}/players so every open waiting room
     *      (including the newly-joined guest's own page after it
     *      subscribes) updates in real time.
     *   6. Return the joined User so the controller can send a
     *      UserGetDTO back in the 201 response.
     *
     * The broadcast payload must be a List<String> of usernames — the
     * frontend's subscription handler expects exactly that shape.
     */
    @Test
    public void joinLobby_valid_addsPlayerSavesAndBroadcastsUpdatedRoster() {
        // given — a waiting lobby with just the host, and a guest user to add
        User guest = new User();
        guest.setId(2L);
        guest.setUsername("guest");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setMaxPlayers(4);
        lobby.setHostUserId(1L);
        lobby.getPlayers().add(host);

        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(lobby);
        given(userRepository.findById(2L)).willReturn(Optional.of(guest));

        // when
        User result = lobbyService.joinLobby("AB-1234", 2L);

        // then — guest joined, lobby persisted, returned user is the guest
        assertEquals(2, lobby.getPlayers().size());
        assertTrue(lobby.getPlayers().contains(host));
        assertTrue(lobby.getPlayers().contains(guest));
        assertEquals(guest, result);
        verify(lobbyRepository).save(lobby);
        verify(lobbyRepository).flush();

        // and the broadcast carries both usernames on /topic/lobby/{code}/players
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List> payloadCaptor = ArgumentCaptor.forClass(List.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/lobby/AB-1234/players"),
                (Object) payloadCaptor.capture());
        List<?> usernames = payloadCaptor.getValue();
        assertEquals(2, usernames.size());
        assertTrue(usernames.contains("host"));
        assertTrue(usernames.contains("guest"));
    }

    /**
     * US4 #75 — Validate lobby code: unknown code -> 404.
     *
     * A user typing a garbage code in the "Join with code" form hits
     * this path. findByLobbyCode returns null, the service must fail
     * with 404 NOT_FOUND before doing anything else.
     */
    @Test
    public void joinLobby_unknownCode_throws404() {
        given(lobbyRepository.findByLobbyCode("XX-0000")).willReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.joinLobby("XX-0000", 2L));
        assertEquals(404, ex.getStatusCode().value());

        // absolutely nothing else should happen
        verify(userRepository, never()).findById(any());
        verify(lobbyRepository, never()).save(any(Lobby.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * US4 #75 — Validate capacity: a full lobby rejects new joins with 409.
     *
     * The host chose maxPlayers at creation time. Once the lobby has
     * exactly that many players, a further join must fail with 409
     * CONFLICT. This protects against race conditions where two users
     * hit Join simultaneously on the last open slot.
     */
    @Test
    public void joinLobby_lobbyFull_throws409() {
        // given — lobby with maxPlayers=2 already containing 2 players
        User existing = new User();
        existing.setId(2L);
        existing.setUsername("existing");

        Lobby full = new Lobby();
        full.setLobbyCode("AB-1234");
        full.setStatus(LobbyStatus.WAITING);
        full.setMaxPlayers(2);
        full.setHostUserId(1L);
        full.getPlayers().add(host);
        full.getPlayers().add(existing);

        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(full);

        // when / then — join must fail with 409 CONFLICT
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.joinLobby("AB-1234", 3L));
        assertEquals(409, ex.getStatusCode().value());

        verify(lobbyRepository, never()).save(any(Lobby.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * US4 #75 — Validate user existence: unknown user id -> 404.
     *
     * If the client sends a user id that doesn't exist (stale session,
     * manually crafted request), the service must 404 rather than
     * persisting a phantom reference into the lobby_players table.
     */
    @Test
    public void joinLobby_unknownUser_throws404() {
        // given — a valid lobby but a non-existent user id
        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setMaxPlayers(4);
        lobby.setHostUserId(1L);
        lobby.getPlayers().add(host);

        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(lobby);
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        // when / then
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.joinLobby("AB-1234", 99L));
        assertEquals(404, ex.getStatusCode().value());

        verify(lobbyRepository, never()).save(any(Lobby.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * US4 #75 — Prevent double-join: user already in the lobby -> 409.
     *
     * If the client retries a join (network blip, double-click), the
     * second call must fail with 409 CONFLICT instead of silently
     * duplicating the user in the lobby_players table — duplicates
     * there would violate the @OneToMany uniqueness and propagate a
     * confusing error to the frontend.
     */
    @Test
    public void joinLobby_userAlreadyInLobby_throws409() {
        // given — host already in the lobby attempts to re-join their own lobby
        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setMaxPlayers(4);
        lobby.setHostUserId(1L);
        lobby.getPlayers().add(host);

        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(lobby);
        given(userRepository.findById(1L)).willReturn(Optional.of(host));

        // when / then
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.joinLobby("AB-1234", 1L));
        assertEquals(409, ex.getStatusCode().value());

        // player count unchanged; no save, no broadcast
        assertEquals(1, lobby.getPlayers().size());
        verify(lobbyRepository, never()).save(any(Lobby.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    // -------------------------------------------------------------------
    // handlePlayerDisconnect — US3 (#69, #70)
    // -------------------------------------------------------------------

    /**
     * US3 #69 — Automatically remove disconnected player from lobby (guest branch).
     *
     * When a non-host user triggers logout (DELETE /users/{id}), the
     * service looks up which lobby they're in and removes them from the
     * players list without deleting the lobby itself. It then broadcasts
     * the trimmed player list to /topic/lobby/{code}/players so the
     * remaining members' waiting-room roster updates in real time.
     *
     * The save() must be called so the join-table row is removed; if
     * it weren't, the remaining players would still see the stale roster
     * after polling.
     */
    @Test
    public void handlePlayerDisconnect_nonHost_removesPlayerAndBroadcastsUpdatedRoster() {
        // given — a lobby with host + guest; guest triggers disconnect
        User guest = new User();
        guest.setId(2L);
        guest.setUsername("guest");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setMaxPlayers(4);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setHostUserId(1L);
        lobby.getPlayers().add(host);
        lobby.getPlayers().add(guest);

        given(lobbyRepository.findByPlayers_Id(2L)).willReturn(Optional.of(lobby));

        // when
        lobbyService.handlePlayerDisconnect(2L);

        // then — guest was removed, host still present, lobby persisted
        assertEquals(1, lobby.getPlayers().size());
        assertTrue(lobby.getPlayers().contains(host));
        assertFalse(lobby.getPlayers().contains(guest));
        verify(lobbyRepository).save(lobby);
        verify(lobbyRepository, never()).delete(any(Lobby.class));

        // and the updated roster was broadcast to the remaining players
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List> payloadCaptor = ArgumentCaptor.forClass(List.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/lobby/AB-1234/players"),
                (Object) payloadCaptor.capture());
        List<?> usernames = payloadCaptor.getValue();
        assertEquals(1, usernames.size());
        assertEquals("host", usernames.get(0));

        // guest leaving should not trigger roundService cleanup (no game)
        verify(roundService, never()).cleanupLobby(anyString());
    }

    /**
     * US3 #70 — Host disconnection logic (end game, notify players).
     *
     * When the host disconnects, the entire lobby is torn down so the
     * game can't hang in a hostless state. This is the most complex
     * branch because it has to run a multi-step cleanup in a specific
     * order to avoid Postgres foreign-key violations on the subsequent
     * User delete:
     *
     *   1. roundService.cleanupLobby — drop any Round rows tied to the
     *      lobby code (Round.lobbyCode has no FK, so Hibernate can't
     *      cascade these automatically).
     *   2. lobby.getPlayers().clear() + saveAndFlush — explicitly empty
     *      the lobby_players join table so no lobby_players.players_id
     *      references survive.
     *   3. lobbyRepository.delete(lobby) + flush — remove the lobby row.
     *   4. Broadcast "HOST_DISCONNECTED" on /topic/lobby/{code}/disconnect
     *      so every other player's waiting-room / game page can kick
     *      them back to /home.
     *
     * The ordering is load-bearing — it was a Postgres FK violation
     * caused by skipping step 2 that motivated the fix recorded in the
     * code-review history. This test uses InOrder verification to guard
     * against a future refactor re-ordering these calls and silently
     * reintroducing the bug.
     */
    @Test
    public void handlePlayerDisconnect_host_cleansUpRoundsClearsPlayersDeletesLobbyAndBroadcasts() {
        // given — a lobby in INGAME, host triggers disconnect
        User guest = new User();
        guest.setId(2L);
        guest.setUsername("guest");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setMaxPlayers(4);
        lobby.setStatus(LobbyStatus.INGAME);
        lobby.setHostUserId(1L);
        lobby.getPlayers().add(host);
        lobby.getPlayers().add(guest);

        given(lobbyRepository.findByPlayers_Id(1L)).willReturn(Optional.of(lobby));

        // when
        lobbyService.handlePlayerDisconnect(1L);

        // then — the players list is empty post-call (join rows cleared)
        assertTrue(lobby.getPlayers().isEmpty(),
                "lobby_players rows must be cleared before the lobby row is deleted");

        // and every step happened in the required order
        InOrder order = inOrder(roundService, lobbyRepository, messagingTemplate);
        order.verify(roundService).cleanupLobby("AB-1234");
        order.verify(lobbyRepository).saveAndFlush(lobby);
        order.verify(lobbyRepository).delete(lobby);
        order.verify(messagingTemplate).convertAndSend(
                eq("/topic/lobby/AB-1234/disconnect"),
                eq((Object) "HOST_DISCONNECTED"));
    }

    /**
     * US3 #69 edge case — if the disconnecting user isn't in any lobby,
     * handlePlayerDisconnect is a no-op.
     *
     * Scenario: a user logs in, never joins or creates a lobby, then
     * logs out. findByPlayers_Id returns an empty Optional. The service
     * must silently return without touching any collaborator — otherwise
     * the subsequent UserService.deleteUser (in UserController) could
     * fail on a phantom lobby operation.
     */
    @Test
    public void handlePlayerDisconnect_userNotInAnyLobby_isNoOp() {
        given(lobbyRepository.findByPlayers_Id(99L)).willReturn(Optional.empty());

        lobbyService.handlePlayerDisconnect(99L);

        verify(roundService, never()).cleanupLobby(anyString());
        verify(lobbyRepository, never()).save(any(Lobby.class));
        verify(lobbyRepository, never()).delete(any(Lobby.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    // -------------------------------------------------------------------
    // startGame — US5 (#81, #82) + safety net for the async reorder fix
    // -------------------------------------------------------------------

    /**
     * US5 #82 — Happy path: start the game.
     *
     * With a valid host, ≥2 players in a WAITING lobby, startGame must:
     *   1. Flip status to INGAME and persist.
     *   2. Broadcast LobbyStartGetDTO on /topic/lobby/{code}/start so
     *      every open waiting-room page routes to /game/{code}.
     *   3. Call RoundService.startRoundWithTimerAsync so the
     *      (potentially slow) Mapillary bootstrap runs on the background
     *      scheduler instead of blocking the HTTP response.
     *
     * The async hand-off is the critical difference vs. the pre-fix
     * code — if startGame went back to calling startRoundWithTimer
     * synchronously, the POST would hang for up to ~30s worst case.
     */
    @Test
    public void startGame_valid_setsInGameBroadcastsAndTriggersRoundAsync() {
        // given — a WAITING lobby with host + one guest
        User guest = new User();
        guest.setId(2L);
        guest.setUsername("guest");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setHostUserId(1L);
        lobby.setMaxPlayers(4);
        lobby.setTotalRounds(3);
        lobby.getPlayers().add(host);
        lobby.getPlayers().add(guest);

        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(lobby);
        given(userRepository.findById(1L)).willReturn(Optional.of(host));
        given(lobbyRepository.save(any(Lobby.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        Lobby result = lobbyService.startGame("AB-1234", 1L);

        // then — status flipped, persisted, broadcast, async bootstrap triggered
        assertEquals(LobbyStatus.INGAME, lobby.getStatus());
        assertEquals(LobbyStatus.INGAME, result.getStatus());
        verify(lobbyRepository).save(lobby);
        verify(lobbyRepository).flush();
        verify(messagingTemplate).convertAndSend(
                eq("/topic/lobby/AB-1234/start"),
                any(Object.class));
        verify(roundService).startRoundWithTimerAsync("AB-1234");

        // crucially: the SYNCHRONOUS round method must NOT be called —
        // that path would block on Mapillary HTTP and re-introduce the
        // "POST hangs for 30s" bug.
        verify(roundService, never()).startRoundWithTimer(anyString());
    }

    /**
     * US5 safety net — Broadcast /start BEFORE the async round bootstrap.
     *
     * This is the load-bearing ordering assertion. The earlier
     * pre-fix code broadcast image[0] inside the synchronous
     * startRoundWithTimer call and only then broadcast /start, which
     * meant every client missed the first image (they hadn't navigated
     * to /game/{code} yet, so they weren't subscribed to
     * /topic/game/{code}/image). That's the "4 images instead of 5"
     * bug. The fix reorders the calls so /start fires first, clients
     * navigate + subscribe, and by the time the Mapillary bootstrap
     * finishes they're ready to receive image[0].
     *
     * If a future refactor swaps the two lines back, this test fails
     * and catches the regression before it reaches production.
     */
    @Test
    public void startGame_broadcastsStartBeforeRoundBootstrap() {
        // given — valid start-game pre-conditions
        User guest = new User();
        guest.setId(2L);
        guest.setUsername("guest");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setHostUserId(1L);
        lobby.setMaxPlayers(4);
        lobby.setTotalRounds(3);
        lobby.getPlayers().add(host);
        lobby.getPlayers().add(guest);

        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(lobby);
        given(userRepository.findById(1L)).willReturn(Optional.of(host));
        given(lobbyRepository.save(any(Lobby.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        lobbyService.startGame("AB-1234", 1L);

        // then — /start broadcast strictly precedes the async bootstrap
        InOrder order = inOrder(messagingTemplate, roundService);
        order.verify(messagingTemplate).convertAndSend(
                eq("/topic/lobby/AB-1234/start"),
                any(Object.class));
        order.verify(roundService).startRoundWithTimerAsync("AB-1234");
    }

    /**
     * US5 #82 — Validate player count: fewer than 2 players -> 400.
     *
     * A GeoGuess round needs at least 2 players to be a game. If the
     * host clicks Start with only themselves in the lobby, the server
     * must refuse with 400 BAD_REQUEST. The frontend disables the
     * Start button client-side when players.length < 2, so reaching
     * this branch means a raced or crafted request.
     */
    @Test
    public void startGame_lessThanTwoPlayers_throws400() {
        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setHostUserId(1L);
        lobby.setMaxPlayers(4);
        lobby.getPlayers().add(host); // only host, no guests

        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(lobby);
        given(userRepository.findById(1L)).willReturn(Optional.of(host));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.startGame("AB-1234", 1L));
        assertEquals(400, ex.getStatusCode().value());

        // no side effects
        verify(lobbyRepository, never()).save(any(Lobby.class));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(roundService, never()).startRoundWithTimerAsync(anyString());
    }

    /**
     * US5 #82 — Authorization: only the host can start the game.
     *
     * If a non-host player sends a start request (manually or via a
     * buggy client), the server must refuse with 403 FORBIDDEN.
     * Crucially this check happens BEFORE status is flipped, so a
     * rejected start request leaves the lobby in its original WAITING
     * state.
     */
    @Test
    public void startGame_requesterNotHost_throws403() {
        User guest = new User();
        guest.setId(2L);
        guest.setUsername("guest");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setHostUserId(1L); // host is user 1
        lobby.setMaxPlayers(4);
        lobby.getPlayers().add(host);
        lobby.getPlayers().add(guest);

        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(lobby);
        // user 2 exists but isn't the host
        given(userRepository.findById(2L)).willReturn(Optional.of(guest));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.startGame("AB-1234", 2L));
        assertEquals(403, ex.getStatusCode().value());

        assertEquals(LobbyStatus.WAITING, lobby.getStatus(), "status must remain WAITING");
        verify(roundService, never()).startRoundWithTimerAsync(anyString());
    }

    /**
     * US5 #82 — Prevent double-start: if the lobby is already INGAME,
     * reject with 409 CONFLICT.
     *
     * This is the defense against the "two pending start requests"
     * symptom observed when the host's button race-conditions a second
     * click during a slow first POST. Returning 409 keeps the lobby
     * from being re-bootstrapped (which would spawn a second scheduler
     * timer, duplicating broadcasts).
     */
    @Test
    public void startGame_alreadyInGame_throws409() {
        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setStatus(LobbyStatus.INGAME); // already started
        lobby.setHostUserId(1L);
        lobby.setMaxPlayers(4);
        lobby.getPlayers().add(host);

        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(lobby);
        given(userRepository.findById(1L)).willReturn(Optional.of(host));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.startGame("AB-1234", 1L));
        assertEquals(409, ex.getStatusCode().value());

        verify(roundService, never()).startRoundWithTimerAsync(anyString());
    }

    /**
     * US5 (supporting) — Unknown lobby code returns 404.
     *
     * Protects against a client with a stale / fabricated code.
     */
    @Test
    public void startGame_unknownCode_throws404() {
        given(lobbyRepository.findByLobbyCode("XX-0000")).willReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.startGame("XX-0000", 1L));
        assertEquals(404, ex.getStatusCode().value());

        // userRepository.findById must not be queried — the lobby check
        // happens first, so a missing lobby short-circuits the whole flow
        verify(userRepository, never()).findById(any());
        verify(roundService, never()).startRoundWithTimerAsync(anyString());
    }

    /**
     * US5 (supporting) — Unknown host user id returns 404.
     *
     * Guards against a lobby that somehow has a dangling host id (for
     * example after a race with user deletion).
     */
    @Test
    public void startGame_unknownHost_throws404() {
        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setHostUserId(1L);
        lobby.setMaxPlayers(4);
        lobby.getPlayers().add(host);

        given(lobbyRepository.findByLobbyCode("AB-1234")).willReturn(lobby);
        given(userRepository.findById(1L)).willReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> lobbyService.startGame("AB-1234", 1L));
        assertEquals(404, ex.getStatusCode().value());

        verify(roundService, never()).startRoundWithTimerAsync(anyString());
    }
}
