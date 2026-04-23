package ch.uzh.ifi.hase.soprafs26.repository;

import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository("answerRepository")
public interface AnswerRepository extends JpaRepository<Answer, Long> {
    boolean existsByRoundIdAndPlayerId(Long roundId, Long playerId);
    List<Answer> findByRoundId(Long roundId);
}