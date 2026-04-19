package ch.uzh.ifi.hase.soprafs26.repository;

import ch.uzh.ifi.hase.soprafs26.entity.Round;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository("roundRepository")
public interface RoundRepository extends JpaRepository<Round, Long> {
    List<Round> findByLobbyCode(String lobbyCode);
}