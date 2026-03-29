package ch.uzh.ifi.hase.soprafs26.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;

@Service
@Transactional
public class LobbyService {

    private final LobbyRepository lobbyRepository;

    public LobbyService(@Qualifier("lobbyRepository") LobbyRepository lobbyRepository) {
        this.lobbyRepository = lobbyRepository;
    }

    public Lobby createLobby(Lobby newLobby) {
        // Logic comes in Commit 2 + 3
        return newLobby;
    }
}