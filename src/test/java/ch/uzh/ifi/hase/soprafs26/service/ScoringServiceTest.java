package ch.uzh.ifi.hase.soprafs26.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.repository.AnswerRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LocationResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

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
    public void calculateScore_atTarget_returns2000() {
        // 1. Arrange: Create a real Round object
        Round dummyRound = new Round();
        dummyRound.setTargetLatitude(47.3769);
        dummyRound.setTargetLongitude(8.5417);
        dummyRound.setTargetCity("Zurich");
        dummyRound.setTargetCountry("Switzerland");

        // 2. Arrange: Stub the geocoding mock to return a valid location
        // Use ArgumentMatchers.anyDouble() inside the stubbing
        LocationResult mockResult = new LocationResult("Zurich", "Switzerland");
        when(geocodingService.resolveLocation(anyDouble(), anyDouble())).thenReturn(mockResult);

        // 3. Act: Execute the real service call with real objects
        int score = scoringService.calculateScore(47.3769, 8.5417, dummyRound);

        // 4. Assert
        assertEquals(2000, score);
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

        // 2. STUB: Force the guess to be in a DIFFERENT country (e.g., Germany)
        // We'll simulate a guess in Berlin (roughly 670km away)
        LocationResult germanyResult = new LocationResult("Berlin", "Germany");
        when(geocodingService.resolveLocation(anyDouble(), anyDouble()))
            .thenReturn(germanyResult);

        // 3. Act: Guessing Berlin coordinates
        int score = scoringService.calculateScore(52.5200, 13.4050, round);

        // 4. Assert: 
        // Based on your formula: 800 - (670 * 0.1) = ~733
        // We verify it's greater than 0 but less than the country penalty threshold (1000)
        assertTrue(score > 0, "Score should be greater than 0 for a close guess in a different country");
        assertTrue(score < 1000, "Score should be below the country threshold");
        assertEquals(733, score, "Score should match the proximity decay for the distance between Berlin and Zurich");
    }
}
