package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;


@Service
public class WebSocketSessionService {
    private final Map<String, Long> sessionToUserMap = new ConcurrentHashMap<>();
    private final LobbyRepository lobbyRepository;

    public WebSocketSessionService(LobbyRepository lobbyRepository) {
        this.lobbyRepository = lobbyRepository;
    }

    public void pair(String sessionId, Long userId) {
        sessionToUserMap.put(sessionId, userId);
    }

    public Long getUserId(String sessionId) {
        return sessionToUserMap.get(sessionId);
    }

    public String getLobbyCodeBySession(String sessionId) {
        Long userId = sessionToUserMap.get(sessionId);
        if (userId != null) {
            return lobbyRepository.findByPlayers_Id(userId)
                    .map(Lobby::getLobbyCode)
                    .orElse(null);
        }
        return null;
    }

    public void remove(String sessionId) {
        sessionToUserMap.remove(sessionId);
    }
}