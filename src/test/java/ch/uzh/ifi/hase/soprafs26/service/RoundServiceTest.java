package ch.uzh.ifi.hase.soprafs26.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import ch.uzh.ifi.hase.soprafs26.constant.ScoreResult;
import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.AnswerRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.RoundRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LocationResult;


/**
 * Unit tests for RoundService.
 *
 * Mocks every dependency (RoundRepository, MapillaryService,
 * SimpMessagingTemplate, LobbyRepository) so these tests exercise pure
 * logic with no HTTP, no DB, no real STOMP broker.
 *
 * A few things are awkward to test in isolation:
 *   - `locationsDataset` is normally populated by @PostConstruct
 *     reading src/main/resources/locations.json. Under @InjectMocks
 *     Spring's lifecycle callbacks don't fire, so we seed the field
 *     with a small controlled list via ReflectionTestUtils for most
 *     tests. One test calls the public loadLocationsDataset() method
 *     directly to prove the JSON file parses.
 *   - The `scheduler` ScheduledExecutorService is constructed inline
 *     in a field initializer, so we can't easily swap it for a
 *     deterministic fake. The timer tests only verify the synchronous
 *     side effects (first image broadcast, future registered in
 *     activeTimers); the 9-second periodic callback would fire well
 *     after the test has passed, and we always stopTimer() to cancel
 *     it so no thread leaks into other tests.
 */
public class RoundServiceTest {

    @Mock
    private RoundRepository roundRepository;

    @Mock
    private MapillaryService mapillaryService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private LobbyRepository lobbyRepository;

    @Mock
    private AnswerRepository answerRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ScoringService scoringService;

    @Mock
    private GeocodingService geocodingService; 

    @InjectMocks
    private RoundService roundService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    // -------------------------------------------------------------------
    // Helper — seed a controlled locations dataset (bypasses @PostConstruct)
    // -------------------------------------------------------------------
    private void seedDataset(RoundService.CuratedLocation... locations) {
        List<RoundService.CuratedLocation> dataset = new ArrayList<>(Arrays.asList(locations));
        ReflectionTestUtils.setField(roundService, "locationsDataset", dataset);
        when(geocodingService.resolveLocation(anyDouble(), anyDouble()))
            .thenReturn(new LocationResult("Test City", "Test Country"));
    }

    // -------------------------------------------------------------------
    // createAndStartRound — US6 (#132, #141)
    // -------------------------------------------------------------------

    /**
     * US6 #132 — Round Image Sequence Logic: happy path.
     *
     * With a single curated location in the dataset and a MapillaryService
     * that returns 5 image URLs on the first try, createAndStartRound must:
     *   - Pick the (only) location in the dataset
     *   - Persist a Round with that location's lat/lon + the 5 URLs
     *   - Return the persisted Round
     *
     * The 5-image count is hard-wired in the production code; the Round
     * entity stores the list as @ElementCollection into ROUND_IMAGES.
     */
    @Test
    public void createAndStartRound_valid_savesRoundWithLocationAnd5Images() {
        // given
        seedDataset(new RoundService.CuratedLocation(40.0, -74.0, "New York"));
        List<String> urls = Arrays.asList("u1", "u2", "u3", "u4", "u5");
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenReturn(urls);
        when(roundRepository.save(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));
        when(geocodingService.resolveLocation(anyDouble(), anyDouble()))
                .thenReturn(new ch.uzh.ifi.hase.soprafs26.rest.dto.LocationResult("NYC", "USA"));

        // when
        Round round = roundService.createAndStartRound("AB-1234");

        // then
        assertNotNull(round);
        assertEquals("AB-1234", round.getLobbyCode());
        assertEquals(5, round.getImageSequence().size());
        assertEquals(40.0, round.getTargetLatitude());
        assertEquals(-74.0, round.getTargetLongitude());
        verify(roundRepository).save(any(Round.class));
    }

    /**
     * US6 #132 — Retry loop.
     *
     * When the first random-location attempt throws (Mapillary returns
     * empty / not enough images / network error), createAndStartRound
     * must catch that, pick another location from the dataset, and
     * retry. This is what gives the feature its robustness: even
     * though many locations on Earth have no Mapillary coverage, we
     * keep trying until one lands.
     *
     * Test setup: force the 1st call to throw, 2nd to return 5 URLs.
     * Expect exactly 2 Mapillary calls and a successful Round return.
     */
    @Test
    public void createAndStartRound_firstAttemptFails_retriesAndSucceeds() {
        // given — two locations so the retry has somewhere to go
        seedDataset(
                new RoundService.CuratedLocation(40.0, -74.0, "NYC"),
                new RoundService.CuratedLocation(35.0, 139.0, "Tokyo"));
        AtomicInteger callCount = new AtomicInteger();
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenAnswer(inv -> {
                    if (callCount.incrementAndGet() == 1) {
                        throw new RuntimeException("no images at first location");
                    }
                    return Arrays.asList("u1", "u2", "u3", "u4", "u5");
                });
        when(roundRepository.save(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        Round round = roundService.createAndStartRound("AB-1234");

        // then — Mapillary was called twice (one fail + one success)
        verify(mapillaryService, times(2))
                .getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5));
        assertNotNull(round);
        assertEquals(5, round.getImageSequence().size());
    }

    /**
     * US6 #141 — Mystery Location Logic: Zurich fallback.
     *
     * The retry loop tries a random location 10 times. If every
     * attempt fails (large parts of Earth have no Mapillary coverage
     * in the tightest bbox radius), the service falls back to Zurich
     * HB coordinates (47.3769, 8.5417) with a wider radius (0.005
     * delta instead of 0.01) — Zurich HB is guaranteed to have
     * thousands of Mapillary images.
     *
     * This test forces the first 10 calls to throw and the 11th
     * (Zurich) to succeed, then asserts the returned Round is
     * persisted with Zurich's exact coordinates.
     */
    @Test
    public void createAndStartRound_all10AttemptsFail_fallsBackToZurich() {
        // given
        seedDataset(new RoundService.CuratedLocation(40.0, -74.0, "NYC"));
        AtomicInteger callCount = new AtomicInteger();
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenAnswer(inv -> {
                    int n = callCount.incrementAndGet();
                    if (n <= 10) {
                        throw new RuntimeException("fail " + n);
                    }
                    // Attempt 11 = Zurich fallback, succeeds
                    return Arrays.asList("z1", "z2", "z3", "z4", "z5");
                });
        when(roundRepository.save(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        Round round = roundService.createAndStartRound("AB-1234");

        // then — 10 random attempts + 1 Zurich attempt = 11 total
        verify(mapillaryService, times(11))
                .getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5));
        assertEquals(47.3769, round.getTargetLatitude());
        assertEquals(8.5417, round.getTargetLongitude());
    }

    /**
     * US6 #141 — Complete Mapillary failure -> 502 BAD_GATEWAY.
     *
     * If every random attempt AND the Zurich fallback throw, we've
     * exhausted our safety net. Rather than persist a Round with no
     * images, the service throws 502 BAD_GATEWAY — "upstream API is
     * unreachable". In practice this only happens when the Mapillary
     * token is invalid / expired or Mapillary itself is down for
     * everyone.
     */
    @Test
    public void createAndStartRound_allAttemptsIncludingZurichFail_throws502() {
        // given
        seedDataset(new RoundService.CuratedLocation(40.0, -74.0, "NYC"));
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenThrow(new RuntimeException("Mapillary is down"));

        // when / then
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> roundService.createAndStartRound("AB-1234"));
        assertEquals(502, ex.getStatusCode().value());

        // 10 random attempts + 1 Zurich attempt, all failing
        verify(mapillaryService, times(11))
                .getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5));
    }

    /**
     * US6 #141 — Empty locations dataset is a 500.
     *
     * If the @PostConstruct dataset-load failed (bad file, missing
     * JSON) and the fallback "add Zurich" also didn't happen for any
     * reason, the list is empty. createAndStartRound short-circuits
     * with HTTP 500 instead of calling random.nextInt(0), which
     * would throw IllegalArgumentException with a less helpful
     * message.
     */
    @Test
    public void createAndStartRound_emptyDataset_throws500() {
        // given — deliberately empty
        ReflectionTestUtils.setField(roundService, "locationsDataset", new ArrayList<>());

        // when / then
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> roundService.createAndStartRound("AB-1234"));
        assertEquals(500, ex.getStatusCode().value());
    }

    // -------------------------------------------------------------------
    // startRoundWithTimer — US6 (#133)
    // -------------------------------------------------------------------

    /**
     * US6 #133 — Broadcast Image on Timer: first image is sent
     * synchronously.
     *
     * startRoundWithTimer does three things:
     *   1. Build a Round via createAndStartRound (5 images).
     *   2. Immediately publish image[0] on /topic/game/{code}/image
     *      with an ImageBroadcastMessage payload.
     *   3. Schedule the remaining 4 images at 9-second intervals via
     *      the internal ScheduledExecutorService.
     *
     * This test verifies (2). The scheduler part (3) fires 9 seconds
     * later and is not asserted here to keep the test deterministic
     * and avoid sleeping / waiting. The broadcast payload's shape is
     * locked in explicitly so any change to the ImageBroadcastMessage
     * DTO breaks this test and alerts us before the frontend breaks.
     *
     * We stopTimer() at the end to cancel the scheduled future so the
     * test-worker thread pool stays clean.
     */
    @Test
    public void startRoundWithTimer_broadcastsFirstImageImmediatelyWithCorrectPayload() {
        // given — one location, 5 images, a lobby with totalRounds=3
        seedDataset(new RoundService.CuratedLocation(40.0, -74.0, "NYC"));
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenReturn(Arrays.asList("url0", "url1", "url2", "url3", "url4"));
        when(roundRepository.save(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setTotalRounds(3);
        when(lobbyRepository.findByLobbyCode("AB-1234")).thenReturn(lobby);

        Round savedRound = new Round();
        savedRound.setLobbyCode("AB-1234");
        when(roundRepository.findByLobbyCode("AB-1234")).thenReturn(List.of(savedRound));

        try {
            // when
            roundService.startRoundWithTimer("AB-1234");

            // then — first image broadcast fired synchronously
            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/game/AB-1234/image"),
                    payloadCaptor.capture());

            Object payload = payloadCaptor.getValue();
            assertTrue(payload instanceof RoundService.ImageBroadcastMessage,
                    "payload must be an ImageBroadcastMessage, got: "
                            + payload.getClass().getName());

            RoundService.ImageBroadcastMessage msg =
                    (RoundService.ImageBroadcastMessage) payload;
            assertEquals("url0", msg.imageUrl, "first image must be images[0]");
            assertEquals(0, msg.index, "first image index must be 0");
            assertEquals(3, msg.totalRounds, "totalRounds pulled from the Lobby");
            assertEquals(1, msg.roundNumber, "roundNumber counts persisted rounds");
        } finally {
            // Always cancel the 9-second periodic future so we don't leak
            // a thread into later tests.
            roundService.stopTimer("AB-1234");
        }
    }

    /**
     * US6 #133 — broadcastImage payload contract.
     *
     * broadcastImage is invoked by the periodic scheduler tick for
     * images 1..4. Calling it directly exercises the same code path
     * the scheduler uses, so if this test passes the 9-second tick
     * also produces the right payload. The alternative (waiting for
     * the scheduler to fire) would add 9 seconds of real time per
     * test run — not worth it.
     */
    @Test
    public void broadcastImage_sendsImageBroadcastMessageToGameTopicWithExactFields() {
        // when
        roundService.broadcastImage("AB-1234",1L, "some-url", 2, 1, 3);

        // then
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/game/AB-1234/image"),
                captor.capture());
        RoundService.ImageBroadcastMessage msg =
                (RoundService.ImageBroadcastMessage) captor.getValue();
        assertEquals("some-url", msg.imageUrl);
        assertEquals(2, msg.index);
        assertEquals(1, msg.roundNumber);
        assertEquals(3, msg.totalRounds);
    }

    // -------------------------------------------------------------------
    // stopTimer — US6 (#134 round lifecycle)
    // -------------------------------------------------------------------

    /**
     * US6 #134 — Round Lifecycle Endpoint: stopTimer cancels and
     * deregisters.
     *
     * stopTimer is how the round-lifecycle tear-down halts the
     * periodic broadcast. It must both:
     *   - Call future.cancel(false) so the scheduler stops firing
     *     the tick lambda.
     *   - Remove the entry from activeTimers so a subsequent lookup
     *     of that lobby code returns no stale future.
     *
     * We reach into activeTimers via ReflectionTestUtils to plant a
     * mocked ScheduledFuture, then verify both effects.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void stopTimer_cancelsActiveFutureAndRemovesFromMap() {
        // given — a mocked future sitting in activeTimers under "AB-1234"
        Map<String, ScheduledFuture<?>> activeTimers =
                (Map<String, ScheduledFuture<?>>) ReflectionTestUtils.getField(
                        roundService, "activeTimers");
        assertNotNull(activeTimers, "activeTimers must be accessible via reflection");
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        activeTimers.put("AB-1234", future);

        // when
        roundService.stopTimer("AB-1234");

        // then — cancel was called with mayInterruptIfRunning=false, map cleared
        verify(future).cancel(false);
        assertFalse(activeTimers.containsKey("AB-1234"));
    }

    /**
     * US6 #134 — stopTimer on a lobby with no active future is a no-op.
     *
     * Defensive: stopTimer may be invoked more than once (future
     * refactors), or called on a lobby that never started a round.
     * Either way it must return quietly without NPE, without touching
     * collaborators.
     */
    @Test
    public void stopTimer_noActiveFutureForLobby_isSilentNoOp() {
        // Nothing in activeTimers — just make sure we don't throw.
        roundService.stopTimer("XX-0000");
        // No assertions needed — test passes if no exception bubbled.
    }

    // -------------------------------------------------------------------
    // cleanupLobby — US6 (#134) + safety net for the host-logout fix
    // -------------------------------------------------------------------

    /**
     * US6 #134 — cleanupLobby deletes every Round row tied to the
     * given lobby code.
     *
     * Round.lobbyCode is a plain String column (no FK), so Hibernate
     * cannot cascade Round deletion when the Lobby is deleted. Instead,
     * LobbyService.removePlayer (host branch) calls roundService
     * .cleanupLobby(code) first so the rows are gone before the lobby
     * itself is removed — otherwise we'd leak orphan Round rows
     * indefinitely.
     *
     * This test verifies both the find + delete calls happen.
     */
    @Test
    public void cleanupLobby_roundsExist_deletesAll() {
        // given — two rounds persisted under the same lobby code
        Round r1 = new Round();
        r1.setLobbyCode("AB-1234");
        Round r2 = new Round();
        r2.setLobbyCode("AB-1234");
        List<Round> rounds = Arrays.asList(r1, r2);
        when(roundRepository.findByLobbyCode("AB-1234")).thenReturn(rounds);
        when(answerRepository.findByRoundId(any())).thenReturn(new ArrayList<>());


        // when
        roundService.cleanupLobby("AB-1234");

        // then — deleteAll was invoked with exactly those rounds
        verify(answerRepository, times(2)).deleteAll(anyList());
        verify(roundRepository).deleteAll(rounds);
    }

    /**
     * US6 #134 — cleanupLobby with no rounds is silent.
     *
     * Calling cleanupLobby for a lobby that never got past the waiting
     * room (no rounds) must not attempt a pointless deleteAll on an
     * empty list. We skip the DB round-trip entirely.
     */
    @Test
    public void cleanupLobby_noRoundsForLobby_doesNotCallDelete() {
        when(roundRepository.findByLobbyCode("AB-1234"))
                .thenReturn(new ArrayList<>());

        roundService.cleanupLobby("AB-1234");

        verify(roundRepository, never()).deleteAll(any());
    }

    // -------------------------------------------------------------------
    // loadLocationsDataset — US6 (#141)
    // -------------------------------------------------------------------

    /**
     * US6 #141 — Mystery Location Logic: the curated dataset exists
     * and parses.
     *
     * locations.json lives in src/main/resources and is loaded by the
     * @PostConstruct hook at boot. This test invokes that method
     * directly (it's public) so we can verify in a plain unit test
     * that:
     *   - The file is still on the classpath
     *   - ObjectMapper can parse it into a List<CuratedLocation>
     *   - The list is non-empty
     *
     * If someone ever deletes or renames the file, or breaks the JSON
     * format, this test fails loudly instead of the backend silently
     * falling back to a 1-entry Zurich-only list.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void loadLocationsDataset_loadsNonEmptyListFromClasspath() {
        // when — manually trigger what @PostConstruct would do at boot
        roundService.loadLocationsDataset();

        // then — the dataset field is populated
        List<RoundService.CuratedLocation> dataset =
                (List<RoundService.CuratedLocation>) ReflectionTestUtils.getField(
                        roundService, "locationsDataset");
        assertNotNull(dataset, "locationsDataset must be initialised");
        assertFalse(dataset.isEmpty(), "locations.json must load at least one curated location");
    }


    // -------------------------------------------------------------------
    // submitAnswer — US7 #102 (POST /round/guess endpoint logic)
    // -------------------------------------------------------------------

    /**
     * US7 #102 / US9 #146 — Happy path: a valid pin submission persists
     * an Answer with the calculated score and is returned to the caller.
     *
     * Wires through the entire submitAnswer flow:
     *   1. Inputs validated (non-null, in range)
     *   2. Lobby + Round + Player looked up
     *   3. Double-submit guard checked
     *   4. Answer persisted with player + round + coords + submittedAt
     *   5. Score computed via Answer.calculateScoreBasedOnDistance
     *      (delegates to ScoringService — math exercised in ScoringServiceTest)
     *   6. WS broadcasts on /answers (US11) and /scores (US9 #147) fire
     *
     * The unit-level scoring math is exercised in ScoringServiceTest
     * and the Haversine math in DistanceCalculatorTest. This test
     * verifies the wire-through and that the score lands on the entity.
     */
    @Test
    public void submitAnswer_validInput_persistsAnswerWithCalculatedScore() {
        // given — a 2-player lobby, an active round, the guest submitting a perfect pin
        User host = new User(); host.setId(1L); host.setUsername("host");
        User guest = new User(); guest.setId(2L); guest.setUsername("guest");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setTotalRounds(3);
        lobby.getPlayers().add(host);
        lobby.getPlayers().add(guest);

        Round round = new Round();
        round.setId(10L);
        round.setLobbyCode("AB-1234");
        round.setTargetLatitude(47.3769);
        round.setTargetLongitude(8.5417);

        when(lobbyRepository.findByLobbyCode("AB-1234")).thenReturn(lobby);
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round));
        when(userRepository.findById(2L)).thenReturn(Optional.of(guest));
        when(answerRepository.existsByRoundIdAndPlayerId(10L, 2L)).thenReturn(false);
        when(answerRepository.save(any(Answer.class))).thenAnswer(inv -> inv.getArgument(0));

        // findByRoundId returns 1 answer (just the new one) — early-end branch must NOT fire
        Answer thisAnswer = new Answer(); thisAnswer.setPlayer(guest);
        when(answerRepository.findByRoundId(10L)).thenReturn(List.of(thisAnswer));

        // ScoringService is mocked — its math is exercised by ScoringServiceTest
        when(scoringService.calculateScore(anyDouble(), anyDouble(), any(Round.class))).thenReturn(2000);
        when(scoringService.getScoreResult(anyDouble(), anyDouble(), any(Round.class)))
                .thenReturn(ScoreResult.CORRECT_CITY);
        when(scoringService.getStandings("AB-1234")).thenReturn(List.of());

        // when — guest submits a pin exactly on the target
        Answer result = roundService.submitAnswer("AB-1234", 10L, 2L, 47.3769, 8.5417);

        // then — the persisted answer carries the right player, coords, and a top-band score
        assertNotNull(result, "submitAnswer must return the persisted Answer");
        assertEquals(guest, result.getPlayer(), "answer.player must be the submitting user");
        assertEquals(round, result.getRound(), "answer.round must be the active round");
        assertEquals(47.3769, result.getLatitude());
        assertEquals(8.5417, result.getLongitude());
        assertNotNull(result.getSubmittedAt(), "submittedAt must be stamped");
        assertEquals(ScoreResult.CORRECT_CITY, result.getScoreResult());
        assertEquals(2000, result.getPointsAwarded());

        verify(answerRepository).save(any(Answer.class));
    }

    /**
     * US7 #102 / US9 #146 — Validation: unknown lobby code returns 404.
     *
     * Representative validation test for the submitAnswer endpoint.
     * Protects against a client with a stale code or a fabricated
     * request. The service must not proceed to look up a round under
     * a non-existent lobby. The other validation branches (null
     * fields, coord bounds, round-not-found, already-finished,
     * already-answered, etc.) follow the same pattern.
     */
    @Test
    public void submitAnswer_lobbyNotFound_throws404() {
        when(lobbyRepository.findByLobbyCode("XX-0000")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> roundService.submitAnswer("XX-0000", 10L, 1L, 0.0, 0.0));
        assertEquals(404, ex.getStatusCode().value());

        verify(roundRepository, never()).findById(any());
        verify(answerRepository, never()).save(any(Answer.class));
    }

    // -------------------------------------------------------------------
    // US8 — 45s round timer + auto-end + early end + broadcasts
    // -------------------------------------------------------------------

    /**
     * US8 #109 — 45-second round timer is set up on the backend.
     *
     * When startRoundWithTimer runs, it calls startCountdownTimer which
     * schedules a per-second countdown task on the ScheduledExecutorService
     * and registers the resulting future in activeCountdownTimers, keyed
     * by lobby code. We can verify the future was registered (via
     * reflection on the private map). The "45 seconds" initial value is
     * encoded in a local `final int[] timeLeft = {45}` inside the
     * scheduler lambda — observable only by waiting for the first tick,
     * which we don't do here to keep the test deterministic.
     *
     * We cancel both schedulers in finally so background threads from
     * the 9-second image rotation and the 1-second countdown don't leak
     * into other tests.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void startRoundWithTimer_registersCountdownFutureFor45SecondTimer() {
        // given
        seedDataset(new RoundService.CuratedLocation(40.0, -74.0, "NYC"));
        when(mapillaryService.getImageSequence(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(5)))
                .thenReturn(Arrays.asList("u0", "u1", "u2", "u3", "u4"));
        when(roundRepository.save(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setTotalRounds(3);
        when(lobbyRepository.findByLobbyCode("AB-1234")).thenReturn(lobby);

        Round savedRound = new Round();
        savedRound.setLobbyCode("AB-1234");
        when(roundRepository.findByLobbyCode("AB-1234")).thenReturn(List.of(savedRound));

        try {
            // when
            roundService.startRoundWithTimer("AB-1234");

            // then — the countdown future is registered, proving the per-second
            // timer task was scheduled (and therefore the 45 → 0 countdown will run).
            Map<String, ?> activeCountdown = (Map<String, ?>)
                    ReflectionTestUtils.getField(roundService, "activeCountdownTimers");
            assertNotNull(activeCountdown, "activeCountdownTimers field must be reachable");
            assertTrue(activeCountdown.containsKey("AB-1234"),
                    "a countdown future must be registered for the lobby after startRoundWithTimer");
        } finally {
            // Cancel both background tasks so they don't outlive the test
            roundService.stopCountdownTimer("AB-1234");
            roundService.stopTimer("AB-1234");
        }
    }

    /**
     * US8 #110 / #111 / #112 — Early round end: when the LAST player
     * submits, the round ends immediately for everyone AND the
     * "ROUND_ENDED" event is broadcast on /topic/game/{code}/roundEnd.
     *
     * This single test covers three dev tasks:
     *   - #111 (early-end trigger condition: all players answered)
     *   - #110 (round-end behavior: the synchronous side effect of
     *     handleRoundEnd that flips finished=true; the timer-zero path
     *     reaches the same handleRoundEnd, so testing the behavior
     *     here also covers the auto-end-at-zero outcome)
     *   - #112 (round-end broadcast: the "ROUND_ENDED" message on the
     *     /roundEnd topic — the per-second timer-tick portion of #112
     *     fires inside a scheduler lambda and is verified at integration
     *     time only)
     *
     * Note: handleRoundEnd schedules a 4-second next-round / game-over
     * task on the real ScheduledExecutorService. That task fires after
     * the test completes; the mocks it eventually hits are no-op stubs
     * by default, so it can't pollute later assertions.
     */
    @Test
    public void submitAnswer_lastPlayerAnswered_triggersEarlyRoundEndAndBroadcastsRoundEndedEvent() {
        // given — 2-player lobby, host already answered, guest now submitting
        User host = new User(); host.setId(1L); host.setUsername("host");
        User guest = new User(); guest.setId(2L); guest.setUsername("guest");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setTotalRounds(1);  // single-round game so the deferred task takes the GAME_OVER branch
        lobby.getPlayers().add(host);
        lobby.getPlayers().add(guest);

        Round round = new Round();
        round.setId(10L);
        round.setLobbyCode("AB-1234");
        round.setTargetLatitude(0.0);
        round.setTargetLongitude(0.0);

        when(lobbyRepository.findByLobbyCode("AB-1234")).thenReturn(lobby);
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round));
        when(userRepository.findById(2L)).thenReturn(Optional.of(guest));
        when(answerRepository.existsByRoundIdAndPlayerId(10L, 2L)).thenReturn(false);
        when(answerRepository.save(any(Answer.class))).thenAnswer(inv -> inv.getArgument(0));

        // After save, findByRoundId returns BOTH answers — host's prior + guest's new
        Answer hostAnswer = new Answer(); hostAnswer.setPlayer(host);
        Answer guestAnswer = new Answer(); guestAnswer.setPlayer(guest);
        when(answerRepository.findByRoundId(10L))
                .thenReturn(Arrays.asList(hostAnswer, guestAnswer));

        // handleRoundEnd reads round count to decide game-over vs next round
        when(roundRepository.findByLobbyCode("AB-1234")).thenReturn(List.of(round));

        // ScoringService stubs (math is exercised in ScoringServiceTest)
        when(scoringService.calculateScore(anyDouble(), anyDouble(), any(Round.class))).thenReturn(2000);
        when(scoringService.getScoreResult(anyDouble(), anyDouble(), any(Round.class)))
                .thenReturn(ScoreResult.CORRECT_CITY);
        when(scoringService.getStandings("AB-1234")).thenReturn(List.of());

        // when
        roundService.submitAnswer("AB-1234", 10L, 2L, 0.0, 0.0);

        // then — round is marked finished (synchronous side effect of handleRoundEnd)
        assertTrue(round.isFinished(),
                "round must be marked finished when the last player answers");

        // and the round-end broadcast went out on the right game-scoped topic with "ROUND_ENDED"
        verify(messagingTemplate).convertAndSend(
                eq("/topic/game/AB-1234/roundEnd"),
                eq((Object) "ROUND_ENDED"));
    }

    /**
     * US8 #111 — Negative case: when not all players have answered, the
     * round does NOT end early.
     *
     * Pairs with the previous test to lock down the comparison
     * direction: the early-end trigger requires `answeredPlayers >=
     * totalPlayers`, not `answeredPlayers > 0` or any other off-by-one
     * variant. A regression that ended the round on the first answer
     * would silently make the game unplayable for groups of 3+.
     */
    @Test
    public void submitAnswer_notLastPlayer_doesNotTriggerEarlyEnd() {
        // given — 3-player lobby, this is only the 2nd answer
        User u1 = new User(); u1.setId(1L); u1.setUsername("u1");
        User u2 = new User(); u2.setId(2L); u2.setUsername("u2");
        User u3 = new User(); u3.setId(3L); u3.setUsername("u3");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setTotalRounds(3);
        lobby.getPlayers().add(u1);
        lobby.getPlayers().add(u2);
        lobby.getPlayers().add(u3);

        Round round = new Round();
        round.setId(10L);
        round.setLobbyCode("AB-1234");
        round.setTargetLatitude(0.0);
        round.setTargetLongitude(0.0);

        when(lobbyRepository.findByLobbyCode("AB-1234")).thenReturn(lobby);
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round));
        when(userRepository.findById(2L)).thenReturn(Optional.of(u2));
        when(answerRepository.existsByRoundIdAndPlayerId(10L, 2L)).thenReturn(false);
        when(answerRepository.save(any(Answer.class))).thenAnswer(inv -> inv.getArgument(0));

        // After save: 2 answers for 3 players — early end MUST NOT fire
        Answer a1 = new Answer(); a1.setPlayer(u1);
        Answer a2 = new Answer(); a2.setPlayer(u2);
        when(answerRepository.findByRoundId(10L)).thenReturn(Arrays.asList(a1, a2));

        when(scoringService.calculateScore(anyDouble(), anyDouble(), any(Round.class))).thenReturn(2000);
        when(scoringService.getScoreResult(anyDouble(), anyDouble(), any(Round.class)))
                .thenReturn(ScoreResult.CORRECT_CITY);
        when(scoringService.getStandings("AB-1234")).thenReturn(List.of());

        // when
        roundService.submitAnswer("AB-1234", 10L, 2L, 0.0, 0.0);

        // then — round still active, no roundEnd broadcast
        assertFalse(round.isFinished(),
                "round must NOT be finished — only 2 of 3 players have answered");
        verify(messagingTemplate, never()).convertAndSend(
                eq("/topic/game/AB-1234/roundEnd"), any(Object.class));
    }

    // -------------------------------------------------------------------
    // US9 — Scoring broadcast (#147)
    // (US9 #143 ScoringService and #146 POST endpoint coverage lives in
    //  ScoringServiceTest and submitAnswer tests above respectively.)
    // -------------------------------------------------------------------

    /**
     * US9 #147 — After an answer is saved, the service broadcasts the
     * live leaderboard to /topic/lobby/{code}/scores.
     *
     * The payload is whatever ScoringService.getStandings(lobbyCode)
     * returns — a sorted list of PlayerStanding records (id / username
     * / totalScore). The frontend can subscribe to this topic to drive
     * a live scoreboard during the round (also a US10 / US12
     * prerequisite).
     *
     * The aggregation logic (the SUM(points_awarded) GROUP BY player_id
     * query) is exercised by ScoringServiceTest separately. Here we
     * verify the wiring: the broadcast goes to the right topic, with
     * the standings list reaching the wire intact.
     */
    @Test
    public void submitAnswer_validInput_broadcastsLeaderboardToScoresTopic() {
        // given — same minimal setup as the answers-broadcast happy path
        User host = new User(); host.setId(1L); host.setUsername("host");
        User guest = new User(); guest.setId(2L); guest.setUsername("guest");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.setTotalRounds(3);
        lobby.getPlayers().add(host);
        lobby.getPlayers().add(guest);

        Round round = new Round();
        round.setId(10L);
        round.setLobbyCode("AB-1234");
        round.setTargetLatitude(0.0);
        round.setTargetLongitude(0.0);

        when(lobbyRepository.findByLobbyCode("AB-1234")).thenReturn(lobby);
        when(roundRepository.findById(10L)).thenReturn(Optional.of(round));
        when(userRepository.findById(2L)).thenReturn(Optional.of(guest));
        when(answerRepository.existsByRoundIdAndPlayerId(10L, 2L)).thenReturn(false);
        when(answerRepository.save(any(Answer.class))).thenAnswer(inv -> inv.getArgument(0));

        Answer thisAnswer = new Answer(); thisAnswer.setPlayer(guest);
        when(answerRepository.findByRoundId(10L)).thenReturn(List.of(thisAnswer));

        when(scoringService.calculateScore(anyDouble(), anyDouble(), any(Round.class))).thenReturn(2000);
        when(scoringService.getScoreResult(anyDouble(), anyDouble(), any(Round.class)))
                .thenReturn(ScoreResult.CORRECT_CITY);
        // Non-empty standings so the broadcast payload is meaningful
        ScoringService.PlayerStanding standing =
                new ScoringService.PlayerStanding(2L, "guest", 2000);
        when(scoringService.getStandings("AB-1234")).thenReturn(List.of(standing));

        // when
        roundService.submitAnswer("AB-1234", 10L, 2L, 0.0, 0.0);

        // then — broadcast went to /scores with the standings list
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/lobby/AB-1234/scores"),
                (Object) captor.capture());
        List<?> payload = captor.getValue();
        assertEquals(1, payload.size(),
                "scores broadcast payload should carry the standings list");
        assertEquals(ScoringService.PlayerStanding.class, payload.get(0).getClass(),
                "scores broadcast must carry PlayerStanding records");
    }
}
