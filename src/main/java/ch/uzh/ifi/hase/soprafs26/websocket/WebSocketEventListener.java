package ch.uzh.ifi.hase.soprafs26.websocket;

import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private final WebSocketSessionService sessionService;
    private final LobbyService lobbyService;

    public WebSocketEventListener(WebSocketSessionService sessionService, LobbyService lobbyService) {
        this.sessionService = sessionService;
        this.lobbyService = lobbyService;
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Long userId = sessionService.getUserId(sessionId);
        sessionService.remove(sessionId);
        if (userId != null) {
            lobbyService.handlePlayerDisconnect(userId);
        }
    }
}