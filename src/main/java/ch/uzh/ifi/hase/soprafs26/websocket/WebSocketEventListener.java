package ch.uzh.ifi.hase.soprafs26.websocket;

import ch.uzh.ifi.hase.soprafs26.service.DisconnectGraceService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private final WebSocketSessionService sessionService;
    private final LobbyService lobbyService;
    private final DisconnectGraceService graceService;

    public WebSocketEventListener(WebSocketSessionService sessionService,
                                  LobbyService lobbyService,
                                  DisconnectGraceService graceService) {
        this.sessionService = sessionService;
        this.lobbyService = lobbyService;
        this.graceService = graceService;
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Long userId = sessionService.getUserId(sessionId);
        sessionService.remove(sessionId);
        if (userId == null) return;

        // Don't fire the disconnect yet — a page refresh closes and reopens the
        // WebSocket within ~1-2s. Only act if no reconnect happens within the grace period.
        final Long uid = userId;
        graceService.scheduleDisconnect(uid, () -> lobbyService.handlePlayerDisconnect(uid));
    }
}
