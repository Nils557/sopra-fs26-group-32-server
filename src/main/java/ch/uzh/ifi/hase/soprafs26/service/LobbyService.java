package ch.uzh.ifi.hase.soprafs26.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;

@Service
@Transactional
public class LobbyService {

    private final Logger log = LoggerFactory.getLogger(LobbyService.class);

    private final LobbyRepository lobbyRepository;

    public LobbyService(@Qualifier("lobbyRepository") LobbyRepository lobbyRepository) {
        this.lobbyRepository = lobbyRepository;
    }

    public Lobby createLobby(Lobby newLobby) {
        // Generate unique lobby code
        newLobby.setLobbyCode(generateLobbyCode());
        
        log.debug("Created lobby with code: {}", newLobby.getLobbyCode());
        return newLobby;
    }

    private String generateLobbyCode() {
        String code;
        do {
            // Generate code like "GX-7742"
            String letters = UUID.randomUUID().toString().substring(0, 2).toUpperCase();
            String numbers = String.valueOf((int)(Math.random() * 9000) + 1000);
            code = letters + "-" + numbers;
        } while (lobbyRepository.findByLobbyCode(code) != null);
        return code;
    }
}