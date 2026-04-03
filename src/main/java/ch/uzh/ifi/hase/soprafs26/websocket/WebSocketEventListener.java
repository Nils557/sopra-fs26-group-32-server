package ch.uzh.ifi.hase.soprafs26.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import ch.uzh.ifi.hase.soprafs26.service.UserService;
import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionService;

@Component
public class WebSocketEventListener {

    private final WebSocketSessionService sessionService;
    private final UserService userService;

    public WebSocketEventListener(WebSocketSessionService sessionService, UserService userService) {
        this.sessionService = sessionService;
        this.userService = userService;
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Long userId = sessionService.getUserId(sessionId);

        if (userId != null) {
            // Trigger the deletion logic we built earlier
            userService.deleteUser(userId);
            sessionService.remove(sessionId);
            System.out.println("User " + userId + " deleted after closing tab.");
        }
    }
}