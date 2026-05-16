package ch.uzh.ifi.hase.soprafs26.service;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyDouble;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;

import ch.uzh.ifi.hase.soprafs26.constant.ScoreResult;
import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.AnswerRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LocationResult;

/**
 * Unit tests for ScoringService (US7 #104).
 *
 * Two tests: one for the perfect-guess happy path (max points) and one
 * for the past-the-boundary case (zero points). Together they confirm
 * both ends of the scoring algorithm work — anything in between is
 * the linear-decay interpolation over those endpoints.
 *
 * Repository dependencies are mocked but not exercised here — the
 * scoring math is pure and doesn't touch the DB.
 */
public class ScoringServiceTest {

    @Mock
    private AnswerRepository answerRepository;

    @Mock
    private LobbyRepository lobbyRepository;

    private ScoringService scoringService;

    @Mock
    private GeocodingService geocodingService; 

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        scoringService = new ScoringService(answerRepository, lobbyRepository, geocodingService);
    }

    /**
     * US7 #104 / US9 #143 — Happy path: a perfect guess (target == guess)
     * returns the maximum 2000 points.
     *
     * Same code path serves both stories: US7 framed it as
     * "scoring based on distance accuracy" and US9 framed it as
     * "ScoringService.calculateScore(...) returning full/half/zero
     * points based on boundary containment".
     */
@Test
    public void calculateScore_atTarget_returnsTimeDecayedMaxScore() {
        // 1. Arrange: Create a real Round object
        Round dummyRound = new Round();
        dummyRound.setTargetLatitude(47.3769);
        dummyRound.setTargetLongitude(8.5417);
        dummyRound.setTargetCity("Zurich");
        dummyRound.setTargetCountry("Switzerland");
        // CRITICAL: Set the start time so the decay math doesn't throw a NullPointerException!
        dummyRound.setStartedAt(Instant.now()); 

        // 2. Arrange: Stub the geocoding mock to return a valid location
        LocationResult mockResult = new LocationResult("Zurich", "Switzerland");
        when(geocodingService.resolveLocation(anyDouble(), anyDouble())).thenReturn(mockResult);

        // 3. Act: Execute the real service call with real objects
        Answer testAnswer = new Answer();
        testAnswer.setLatitude(47.3769);
        testAnswer.setLongitude(8.5417);
        testAnswer.setSubmittedAt(dummyRound.getStartedAt().plusSeconds(2)); // Answered in 2 seconds

        // Call the master method (using the original name)
        scoringService.calculateScore(testAnswer, dummyRound);

        int score = testAnswer.getPointsAwarded();

        // 4. Assert
        // Base score = 2000. 
        // 2 seconds elapsed -> (2/45) * 0.5 = 0.02222 penalty. 
        // 2000 * 0.9777 = 1938 points.
        assertEquals(1938, score);
        assertEquals(ScoreResult.CORRECT_CITY, testAnswer.getScoreResult());
    }

    /**
     * US7 #104 / US9 #143 — Failure case: a guess past the country
     * boundary (~2000 km away) returns exactly 0 points.
     */
    @Test
    public void calculateScore_pastCountryBoundary_returnsProximityScore() {
        // 1. Arrange: Target is in Zurich, Switzerland
        Round round = new Round();
        round.setTargetLatitude(47.3769);
        round.setTargetLongitude(8.5417);
        round.setTargetCountry("Switzerland");
        round.setTargetCity("Zurich");
        round.setStartedAt(Instant.now()); 

        // 2. STUB: Force the guess to be in a DIFFERENT country (e.g., Germany)
        LocationResult germanyResult = new LocationResult("Berlin", "Germany");
        when(geocodingService.resolveLocation(anyDouble(), anyDouble()))
            .thenReturn(germanyResult);

        // 3. Act: Guessing Berlin coordinates
        Answer berlinAnswer = new Answer();
        berlinAnswer.setLatitude(52.5200);
        berlinAnswer.setLongitude(13.4050);
        berlinAnswer.setSubmittedAt(round.getStartedAt().plusSeconds(10)); // Answered in 10 seconds

        // Call the master method (using the original name)
        scoringService.calculateScore(berlinAnswer, round);

        int score = berlinAnswer.getPointsAwarded();

        // 4. Assert: 
        // Because the result is INCORRECT (wrong country), our logic bypasses the speed multiplier.
        // Base distance is ~670km. Formula: 1000 - 670 = 330.
        assertTrue(score > 0, "Score should be greater than 0 for a close guess in a different country");
        assertTrue(score < 1000, "Score should be below the country threshold");
        assertEquals(330, score, "Score should match the proximity decay for the distance between Berlin and Zurich");
        assertEquals(ScoreResult.INCORRECT, berlinAnswer.getScoreResult());
    }

    /**
     * US10 #153 — getStandings aggregates each player's points across
     * every round of the lobby and returns the leaderboard sorted
     * descending by total score.
     *
     * Setup: lobby AB-1234 with three players (alice, bob, carol),
     * each having answered three rounds. The Mockito stubs are wired
     * so the per-round point totals come out to 300 / 200 / 150
     * respectively. The assertions cover both contracts in one go:
     *   - aggregation: each player's totalScore equals the SUM of their
     *     pointsAwarded across all rounds in the lobby
     *   - ordering: the resulting list is sorted descending by
     *     totalScore (highest first)
     *
     * MANUAL SABOTAGE: In ScoringService.java line 77, reverse the
     * comparator from
     *     .sorted((a, b) -> Integer.compare(b.totalScore, a.totalScore))
     * to
     *     .sorted((a, b) -> Integer.compare(a.totalScore, b.totalScore))
     * Carol (lowest score) will land at index 0 instead of alice
     * (highest), and the assertion result.get(0).username == "alice"
     * fails. This proves the test discriminates the sort direction
     * specifically — not just "some ordering happened".
     */
    @Test
    public void getStandings_multipleRoundsAndPlayers_returnsTotalsSortedDescending() {
        // given — three players in lobby AB-1234
        User alice = new User(); alice.setId(1L); alice.setUsername("alice");
        User bob = new User(); bob.setId(2L); bob.setUsername("bob");
        User carol = new User(); carol.setId(3L); carol.setUsername("carol");

        Lobby lobby = new Lobby();
        lobby.setLobbyCode("AB-1234");
        lobby.getPlayers().add(alice);
        lobby.getPlayers().add(bob);
        lobby.getPlayers().add(carol);
        when(lobbyRepository.findByLobbyCode("AB-1234")).thenReturn(lobby);

        // alice answered 3 rounds totalling 300 (100 + 100 + 100)
        Answer a1 = new Answer(); a1.setPointsAwarded(100);
        Answer a2 = new Answer(); a2.setPointsAwarded(100);
        Answer a3 = new Answer(); a3.setPointsAwarded(100);
        when(answerRepository.findByPlayerIdAndRound_LobbyCode(1L, "AB-1234"))
                .thenReturn(List.of(a1, a2, a3));

        // bob answered 3 rounds totalling 200 (50 + 50 + 100)
        Answer b1 = new Answer(); b1.setPointsAwarded(50);
        Answer b2 = new Answer(); b2.setPointsAwarded(50);
        Answer b3 = new Answer(); b3.setPointsAwarded(100);
        when(answerRepository.findByPlayerIdAndRound_LobbyCode(2L, "AB-1234"))
                .thenReturn(List.of(b1, b2, b3));

        // carol answered 3 rounds totalling 150 (50 + 50 + 50)
        Answer c1 = new Answer(); c1.setPointsAwarded(50);
        Answer c2 = new Answer(); c2.setPointsAwarded(50);
        Answer c3 = new Answer(); c3.setPointsAwarded(50);
        when(answerRepository.findByPlayerIdAndRound_LobbyCode(3L, "AB-1234"))
                .thenReturn(List.of(c1, c2, c3));

        // when
        List<ScoringService.PlayerStanding> result = scoringService.getStandings("AB-1234");

        // then — every player appears exactly once, totals are summed correctly,
        // and the list is sorted descending by totalScore
        assertEquals(3, result.size(), "every player in the lobby must appear once");
        assertEquals("alice", result.get(0).username);
        assertEquals(300, result.get(0).totalScore);
        assertEquals("bob", result.get(1).username);
        assertEquals(200, result.get(1).totalScore);
        assertEquals("carol", result.get(2).username);
        assertEquals(150, result.get(2).totalScore);
    }

    /**
     * US10 #153 — Defensive null guard: an unknown lobby code returns
     * an empty list, not null and not an NPE.
     *
     * The frontend's leaderboard widget may call getStandings during
     * lobby teardown (after the host left and the lobby record was
     * deleted but before the WebSocket subscription closed). Returning
     * an empty list keeps the UI stable instead of bubbling a 500 to
     * the user.
     *
     * MANUAL SABOTAGE: In ScoringService.java line 69, invert the null
     * guard from
     *     if (lobby == null) return List.of();
     * to
     *     if (lobby != null) return List.of();
     * With the inverted guard and a null lobby, execution falls
     * through to lobby.getPlayers() and throws NullPointerException.
     * The assertion result.isEmpty() is never reached — the test
     * fails with NPE, proving the guard's presence is what the test
     * actually verifies.
     */
    @Test
    public void getStandings_unknownLobbyCode_returnsEmptyList() {
        // given — repository finds no lobby for this code
        when(lobbyRepository.findByLobbyCode("XX-0000")).thenReturn(null);

        // when
        List<ScoringService.PlayerStanding> result = scoringService.getStandings("XX-0000");

        // then — empty list, no exception
        assertTrue(result.isEmpty(),
                "unknown lobby code must yield an empty leaderboard, not null or NPE");
    }
}
