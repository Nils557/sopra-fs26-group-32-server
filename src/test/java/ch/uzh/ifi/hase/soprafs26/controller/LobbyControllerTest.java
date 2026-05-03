package ch.uzh.ifi.hase.soprafs26.controller;

import ch.uzh.ifi.hase.soprafs26.constant.LobbyStatus;
import ch.uzh.ifi.hase.soprafs26.constant.ScoreResult;
import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.AnswerPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyJoinRequestDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyPostDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LobbyStartRequestDTO;
import ch.uzh.ifi.hase.soprafs26.rest.dto.RoundSummaryGetDTO;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import ch.uzh.ifi.hase.soprafs26.service.RoundService;
import ch.uzh.ifi.hase.soprafs26.service.ScoringService;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvcTest slice for LobbyController.
 *
 * Covers the REST surface for US2 (create), US4 (join), US5 (start), and
 * the simple GET endpoints. The service layer is fully mocked via
 * @MockitoBean — these tests only verify HTTP binding, status codes,
 * DTO (de)serialization, and delegation to the service. Business rules
 * (validation, uniqueness, WS broadcasts) live in LobbyService and are
 * covered in LobbyServiceTest.
 *
 * LobbyController declares RoundService as a constructor parameter but
 * does not use it — we still need to register a @MockitoBean for it so
 * the Spring test context can wire the controller. If that injection is
 * ever cleaned up, this mock can be removed too.
 */
@WebMvcTest(LobbyController.class)
public class LobbyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LobbyService lobbyService;

    @MockitoBean
    private RoundService roundService;

    /**
     * US2 #62 — POST /lobbies endpoint for lobby creation.
     *
     * The "Create lobby" page sends a LobbyPostDTO with the host's user
     * id plus the desired rounds count and max players. The controller
     * must:
     *   1. Deserialize the request body into a LobbyPostDTO.
     *   2. Delegate to LobbyService.createLobby (which runs validation,
     *      generates the unique lobby code, and persists).
     *   3. Return HTTP 201 CREATED with a LobbyGetDTO containing the
     *      generated lobbyCode, the config echoed back, and the
     *      WAITING status. The frontend uses the lobbyCode to route
     *      the host to /lobbies/{code} immediately.
     *
     * The service is mocked, so this test does not exercise code
     * generation or validation logic — see LobbyServiceTest for those.
     */
    @Test
    public void createLobby_validInput_returns201WithLobbyGetDTO() throws Exception {
        // given — client sends config, service "returns" a persisted lobby
        LobbyPostDTO post = new LobbyPostDTO();
        post.setHostUserId(1L);
        post.setMaxPlayers(4);
        post.setTotalRounds(3);

        Lobby created = new Lobby();
        created.setId(100L);
        created.setLobbyCode("AB-1234");
        created.setMaxPlayers(4);
        created.setTotalRounds(3);
        created.setStatus(LobbyStatus.WAITING);
        created.setHostUserId(1L);
        given(lobbyService.createLobby(Mockito.any())).willReturn(created);

        // when — POST /lobbies with JSON body
        MockHttpServletRequestBuilder req = post("/lobbies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(asJsonString(post));

        // then — 201 + LobbyGetDTO JSON with generated lobbyCode and config
        mockMvc.perform(req)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lobbyCode", is("AB-1234")))
                .andExpect(jsonPath("$.maxPlayers", is(4)))
                .andExpect(jsonPath("$.totalRounds", is(3)))
                .andExpect(jsonPath("$.hostUserId", is(1)));
    }

    /**
     * US2 (supporting) — GET /lobbies/{code} retrieves a lobby by code.
     *
     * This is used by the waiting room + the /home page's "join via
     * code" form to fetch lobby metadata (maxPlayers, status,
     * hostUsername). It was also wired into the polling fallback fix
     * so non-host players catch `status == INGAME` if they missed the
     * /start broadcast.
     *
     * The controller delegates straight to LobbyService.getLobby which
     * is responsible for enriching the DTO with hostUsername (resolved
     * via UserRepository). Service is mocked here.
     */
    @Test
    public void getLobby_existingCode_returnsLobbyGetDTO() throws Exception {
        // given — service returns a fully-populated DTO
        LobbyGetDTO dto = new LobbyGetDTO();
        dto.setId(100L);
        dto.setLobbyCode("AB-1234");
        dto.setMaxPlayers(4);
        dto.setTotalRounds(3);
        dto.setStatus(LobbyStatus.WAITING);
        dto.setHostUserId(1L);
        dto.setHostUsername("hostUser");
        given(lobbyService.getLobby("AB-1234")).willReturn(dto);

        // when — GET /lobbies/{code}
        // then — 200 OK + the DTO echoed as JSON
        mockMvc.perform(get("/lobbies/{code}", "AB-1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lobbyCode", is("AB-1234")))
                .andExpect(jsonPath("$.maxPlayers", is(4)))
                .andExpect(jsonPath("$.totalRounds", is(3)))
                .andExpect(jsonPath("$.hostUsername", is("hostUser")));
    }

    /**
     * US4 #74 — POST /lobbies/{code}/players (join lobby) endpoint.
     *
     * When the /home page's "Join with code" form is submitted, the
     * client issues a POST /lobbies/{code}/players with a
     * LobbyJoinRequestDTO carrying the user id. The controller must:
     *   1. Parse the lobby code from the path and the user id from the
     *      request body.
     *   2. Delegate to LobbyService.joinLobby (the service is where
     *      validation lives — capacity, status, duplicate checks — all
     *      covered in LobbyServiceTest).
     *   3. Return HTTP 201 CREATED and a UserGetDTO describing the user
     *      who just joined. The frontend uses this to populate the
     *      waiting-room identity.
     *
     * NB: the construction-sheet task title calls this endpoint
     * "POST /lobbies/{code}/join" but the actual implementation uses
     * "/players" (a closer fit for REST-style sub-resources). This test
     * locks in the real URL.
     */
    @Test
    public void joinLobby_validInput_returns201WithUserGetDTO() throws Exception {
        // given — request body with the user id, and the service's return value
        LobbyJoinRequestDTO body = new LobbyJoinRequestDTO();
        body.setUserId(7L);

        User joined = new User();
        joined.setId(7L);
        joined.setUsername("guest");
        given(lobbyService.joinLobby(Mockito.eq("AB-1234"), Mockito.eq(7L))).willReturn(joined);

        // when — POST /lobbies/AB-1234/players
        MockHttpServletRequestBuilder req = post("/lobbies/{code}/players", "AB-1234")
                .contentType(MediaType.APPLICATION_JSON)
                .content(asJsonString(body));

        // then — 201 + UserGetDTO with id + username
        mockMvc.perform(req)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(7)))
                .andExpect(jsonPath("$.username", is("guest")));
    }

    /**
     * US4 (supporting) — GET /lobbies/{code}/players returns the current
     * roster as a JSON array of usernames.
     *
     * The waiting room uses this endpoint both on initial load and in
     * its 4-second polling fallback (the safety net for missed STOMP
     * broadcasts). If this endpoint breaks, the roster silently stops
     * updating for players whose WebSocket dropped.
     */
    @Test
    public void getPlayers_returnsUsernamesAsJsonArray() throws Exception {
        // given — service returns a two-player roster
        given(lobbyService.getPlayers("AB-1234"))
                .willReturn(Arrays.asList("host", "guest1"));

        // when / then — GET returns 200 + the two usernames in order
        mockMvc.perform(get("/lobbies/{code}/players", "AB-1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]", is("host")))
                .andExpect(jsonPath("$[1]", is("guest1")));
    }

    /**
     * US5 #81 — POST /lobbies/{code}/start endpoint.
     *
     * When the host clicks "Start Game" in the waiting room, the
     * client sends a POST with a LobbyStartRequestDTO carrying the
     * host's user id (so the backend can verify the caller is
     * actually the host). The controller must:
     *   1. Parse the code from the path and the hostUserId from the
     *      body.
     *   2. Delegate to LobbyService.startGame — which does all the
     *      validation (≥2 players, caller is host, status wasn't
     *      already INGAME, lobby exists), broadcasts /start, and
     *      kicks off the async round bootstrap. Those branches are
     *      covered in LobbyServiceTest.
     *   3. Return HTTP 201 CREATED with a LobbyStartGetDTO containing
     *      the lobbyCode and the new status (INGAME). The frontend
     *      uses this response mainly as a signal that the POST
     *      succeeded — navigation is driven by the STOMP /start
     *      broadcast (or the polling fallback on stuck clients).
     */
    @Test
    public void startGame_validHost_returns201WithLobbyStartGetDTO() throws Exception {
        // given — request body carrying hostUserId, service returns started lobby
        LobbyStartRequestDTO body = new LobbyStartRequestDTO();
        body.setHostUserId(1L);

        Lobby started = new Lobby();
        started.setLobbyCode("AB-1234");
        started.setStatus(LobbyStatus.INGAME);
        given(lobbyService.startGame(Mockito.eq("AB-1234"), Mockito.eq(1L))).willReturn(started);

        // when — POST /lobbies/AB-1234/start
        MockHttpServletRequestBuilder req = post("/lobbies/{code}/start", "AB-1234")
                .contentType(MediaType.APPLICATION_JSON)
                .content(asJsonString(body));

        // then — 201 + LobbyStartGetDTO with lobbyCode + INGAME status
        mockMvc.perform(req)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lobbyCode", is("AB-1234")))
                .andExpect(jsonPath("$.status", is("INGAME")));
    }

    /**
     * US7 #102 / US9 #146 — POST /lobbies/{code}/rounds/{roundId}/answers
     * (submit pin) endpoint.
     *
     * The controller parses the path + body and delegates to
     * RoundService.submitAnswer, which is where validation, scoring,
     * persistence, and the WS broadcasts live (covered in
     * RoundServiceTest). On success the response is HTTP 201 with an
     * AnswerGetDTO carrying the saved id, echoed coords, score result,
     * points awarded, and the submission timestamp.
     *
     * NB: the dev-task title calls this "POST .../guess" but the actual
     * route is .../answers (REST sub-resource style). This test pins
     * the real URL.
     */
    @Test
    public void submitAnswer_validInput_returns201WithAnswerGetDTO() throws Exception {
        // given — request body and the service's "persisted" return value
        AnswerPostDTO body = new AnswerPostDTO();
        body.setPlayerId(7L);
        body.setLatitude(47.3769);
        body.setLongitude(8.5417);

        Round round = new Round();
        round.setId(10L);
        round.setLobbyCode("AB-1234");

        User player = new User();
        player.setId(7L);
        player.setUsername("guest");

        Answer persisted = new Answer();
        persisted.setId(100L);
        persisted.setLatitude(47.3769);
        persisted.setLongitude(8.5417);
        persisted.setRound(round);
        persisted.setPlayer(player);
        persisted.setSubmittedAt(Instant.parse("2026-05-03T12:00:00Z"));
        persisted.setScoreResult(ScoreResult.CORRECT_CITY);
        persisted.setPointsAwarded(2000);

        given(roundService.submitAnswer(
                Mockito.eq("AB-1234"),
                Mockito.eq(10L),
                Mockito.eq(7L),
                Mockito.eq(47.3769),
                Mockito.eq(8.5417)))
                .willReturn(persisted);

        // when — POST /lobbies/AB-1234/rounds/10/answers
        MockHttpServletRequestBuilder req = post(
                "/lobbies/{code}/rounds/{roundId}/answers", "AB-1234", 10L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(asJsonString(body));

        // then — 201 + AnswerGetDTO JSON with id + coords + score
        mockMvc.perform(req)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(100)))
                .andExpect(jsonPath("$.latitude", is(47.3769)))
                .andExpect(jsonPath("$.longitude", is(8.5417)))
                .andExpect(jsonPath("$.scoreResult", is("CORRECT_CITY")))
                .andExpect(jsonPath("$.pointsAwarded", is(2000)));
    }

    /**
     * US10 — GET /lobbies/{code}/rounds/{roundId}/summary endpoint.
     *
     * Returns the round summary screen's data: correct city / country /
     * coords, plus the live leaderboard sorted by total score. Powered
     * by RoundService.getRoundSummary which builds the DTO from the
     * round + ScoringService.getStandings(...). Service is mocked here
     * — this test verifies the HTTP wiring and the JSON shape so the
     * frontend's summary overlay can rely on the field names.
     */
    @Test
    public void getRoundSummary_existingRound_returns200WithRoundSummaryGetDTO() throws Exception {
        // given — service returns a populated DTO with one player on the leaderboard
        RoundSummaryGetDTO dto = new RoundSummaryGetDTO();
        dto.setRoundId(10L);
        dto.setCorrectCity("Zurich");
        dto.setCorrectCountry("Switzerland");
        dto.setCorrectLatitude(47.3769);
        dto.setCorrectLongitude(8.5417);

        ScoringService.PlayerStanding standing =
                new ScoringService.PlayerStanding(7L, "guest", 2000);
        dto.setStandings(List.of(standing));

        given(roundService.getRoundSummary("AB-1234", 10L)).willReturn(dto);

        // when / then — GET returns 200 + the DTO echoed as JSON
        mockMvc.perform(get("/lobbies/{code}/rounds/{roundId}/summary", "AB-1234", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundId", is(10)))
                .andExpect(jsonPath("$.correctCity", is("Zurich")))
                .andExpect(jsonPath("$.correctCountry", is("Switzerland")))
                .andExpect(jsonPath("$.correctLatitude", is(47.3769)))
                .andExpect(jsonPath("$.correctLongitude", is(8.5417)))
                .andExpect(jsonPath("$.standings[0].username", is("guest")))
                .andExpect(jsonPath("$.standings[0].totalScore", is(2000)));
    }

    /**
     * Helper to convert a DTO to a JSON string for MockMvc request
     * bodies. Same pattern as UserControllerTest#asJsonString.
     */
    private String asJsonString(final Object object) {
        try {
            return new ObjectMapper().writeValueAsString(object);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    String.format("The request body could not be created.%s", e.toString()));
        }
    }
}
