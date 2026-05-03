package ch.uzh.ifi.hase.soprafs26.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import ch.uzh.ifi.hase.soprafs26.entity.Answer;

@Repository("answerRepository")
public interface AnswerRepository extends JpaRepository<Answer, Long> {
    boolean existsByRoundIdAndPlayerId(Long roundId, Long playerId);
    List<Answer> findByRoundId(Long roundId);
    List<Answer> findByPlayerId(Long playerId);
    List<Answer> findByPlayerIdAndRound_LobbyCode(Long playerId, String lobbyCode);
}

