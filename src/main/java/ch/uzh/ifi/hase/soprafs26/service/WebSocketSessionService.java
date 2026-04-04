package ch.uzh.ifi.hase.soprafs26.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class WebSocketSessionService {
    private final Map<String, Long> sessionToUserMap = new ConcurrentHashMap<>();

    public void pair(String sessionId, Long userId) {
        sessionToUserMap.put(sessionId, userId);
    }

    public Long getUserId(String sessionId) {
        return sessionToUserMap.get(sessionId);
    }

    public void remove(String sessionId) {
        sessionToUserMap.remove(sessionId);
    }
}