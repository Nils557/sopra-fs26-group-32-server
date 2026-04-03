package ch.uzh.ifi.hase.soprafs26.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;

@Repository("lobbyRepository")
public interface LobbyRepository extends JpaRepository<Lobby, Long> {
    Lobby findByLobbyCode(String lobbyCode);
    
    // Finds the Lobby that contains a User with this specific ID in its 'players' list
    Optional<Lobby> findByPlayers_Id(Long userId);
}
