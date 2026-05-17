package ch.uzh.ifi.hase.soprafs26.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import ch.uzh.ifi.hase.soprafs26.entity.Answer;

@Repository("answerRepository")
public interface AnswerRepository extends JpaRepository<Answer, Long> {
    boolean existsByRoundIdAndPlayerId(Long roundId, Long playerId);
    List<Answer> findByRoundId(Long roundId);
    List<Answer> findByPlayerId(Long playerId);
    List<Answer> findByPlayerIdAndRound_LobbyCode(Long playerId, String lobbyCode);

    @Transactional
    @Modifying
    @Query("DELETE FROM Answer a WHERE a.player.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}