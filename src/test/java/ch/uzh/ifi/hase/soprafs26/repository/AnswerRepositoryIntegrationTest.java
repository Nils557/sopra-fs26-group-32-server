package ch.uzh.ifi.hase.soprafs26.repository;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import ch.uzh.ifi.hase.soprafs26.constant.ScoreResult;
import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.entity.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for AnswerRepository (US9 #145).
 *
 * The "round_results" table from the dev task description is implemented
 * as the existing `answer` table — it carries round_id, player_id,
 * score (pointsAwarded), guessed_lat (latitude), guessed_lng (longitude),
 * plus the score_result enum and a submitted_at timestamp.
 *
 * These tests run under @DataJpaTest with an embedded H2 schema that
 * mirrors production. They exercise:
 *   1. The full save/reload round-trip — every required column maps
 *      correctly and survives a flush + clear.
 *   2. The derived existsByRoundIdAndPlayerId query that submitAnswer
 *      uses for its double-submit guard.
 */
@DataJpaTest
public class AnswerRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AnswerRepository answerRepository;

    /** Helper — persists a Round with the required non-null columns. */
    private Round persistRound() {
        Round round = new Round();
        round.setLobbyCode("AB-1234");
        round.setTargetLatitude(47.3769);
        round.setTargetLongitude(8.5417);
        entityManager.persist(round);
        return round;
    }

    /** Helper — persists a User with the given username. */
    private User persistUser(String username) {
        User user = new User();
        user.setUsername(username);
        entityManager.persist(user);
        return user;
    }

    /**
     * US9 #145 — Persisting an Answer round-trips every score-related
     * column: pointsAwarded, scoreResult, latitude, longitude, plus
     * the round + player FKs and the submittedAt timestamp.
     *
     * If a future refactor drops one of these columns or changes the
     * type (e.g. int → String for points), this test fails immediately
     * instead of letting silent data loss reach production. The
     * entityManager.clear() call forces a real DB read instead of
     * returning the session-cached instance.
     */
    @Test
    public void save_thenReload_persistsAllScoreFields() {
        // given — required FKs
        User player = persistUser("guest");
        Round round = persistRound();

        Answer answer = new Answer();
        answer.setLatitude(47.3769);
        answer.setLongitude(8.5417);
        answer.setRound(round);
        answer.setPlayer(player);
        answer.setSubmittedAt(Instant.parse("2026-05-03T12:00:00Z"));
        answer.setScoreResult(ScoreResult.CORRECT_CITY);
        answer.setPointsAwarded(2000);

        // when — save + flush + clear forces a real DB round-trip
        Answer saved = answerRepository.saveAndFlush(answer);
        Long id = saved.getId();
        assertNotNull(id, "id must be generated on save");
        entityManager.clear();

        // then — every column survives the round-trip
        Answer reloaded = answerRepository.findById(id).orElseThrow();
        assertEquals(47.3769, reloaded.getLatitude());
        assertEquals(8.5417, reloaded.getLongitude());
        assertEquals(2000, reloaded.getPointsAwarded());
        assertEquals(ScoreResult.CORRECT_CITY, reloaded.getScoreResult());
        assertNotNull(reloaded.getSubmittedAt(), "submittedAt must be persisted");
        assertEquals(round.getId(), reloaded.getRound().getId(),
                "round FK must be persisted");
        assertEquals(player.getId(), reloaded.getPlayer().getId(),
                "player FK must be persisted");
    }

    /**
     * US9 #145 — Derived query existsByRoundIdAndPlayerId works after
     * persisting an Answer.
     *
     * This query powers the double-submit guard in
     * RoundService.submitAnswer (US7 AC3 / US9 AC: pin can't be changed
     * once submitted). If the derived-query naming convention breaks
     * (e.g. a future refactor renames the player field to user), the
     * service would silently allow duplicate answers. This test pins
     * the contract for both the positive and negative cases.
     */
    @Test
    public void existsByRoundIdAndPlayerId_returnsTrueAfterSaveFalseOtherwise() {
        // given — persist an Answer for (round, player)
        User player = persistUser("guest");
        Round round = persistRound();

        Answer answer = new Answer();
        answer.setLatitude(0.0);
        answer.setLongitude(0.0);
        answer.setRound(round);
        answer.setPlayer(player);
        answer.setSubmittedAt(Instant.now());
        answer.setPointsAwarded(0);
        answerRepository.saveAndFlush(answer);
        entityManager.clear();

        // then — exists returns true for the saved (round, player) pair
        assertTrue(answerRepository.existsByRoundIdAndPlayerId(round.getId(), player.getId()),
                "exists must return true after persisting an Answer for (round, player)");
        // and false for a player who hasn't answered this round
        assertFalse(answerRepository.existsByRoundIdAndPlayerId(round.getId(), 9999L),
                "exists must return false for a player who hasn't answered this round");
    }
}
