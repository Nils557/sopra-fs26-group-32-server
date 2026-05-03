package ch.uzh.ifi.hase.soprafs26.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import ch.uzh.ifi.hase.soprafs26.repository.AnswerRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        scoringService = new ScoringService(answerRepository, lobbyRepository);
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
        int score = scoringService.calculateScore(47.3769, 8.5417, 47.3769, 8.5417);
        assertEquals(2000, score, "perfect guess must award the maximum 2000 points");
    }

    /**
     * US7 #104 / US9 #143 — Failure case: a guess past the country
     * boundary (~2000 km away) returns exactly 0 points.
     */
    @Test
    public void calculateScore_pastCountryBoundary_returnsZero() {
        // 18° lat at equator ≈ 2001 km — well past the 1000 km country boundary
        int score = scoringService.calculateScore(18.0, 0.0, 0.0, 0.0);
        assertEquals(0, score, "guesses past 1000 km must give exactly 0 points");
    }
}
