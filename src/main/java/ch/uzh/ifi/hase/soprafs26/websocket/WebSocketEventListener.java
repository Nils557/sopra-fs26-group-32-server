package ch.uzh.ifi.hase.soprafs26.websocket;

import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private final WebSocketSessionService sessionService;

    public WebSocketEventListener(WebSocketSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        sessionService.remove(event.getSessionId());
    }
}
