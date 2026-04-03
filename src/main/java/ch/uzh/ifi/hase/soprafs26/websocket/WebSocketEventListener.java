package ch.uzh.ifi.hase.soprafs26.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import ch.uzh.ifi.hase.soprafs26.service.UserService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionService;
import jakarta.transaction.Transactional;

@Component
public class WebSocketEventListener {

    private final WebSocketSessionService sessionService;
    private final UserService userService;
    private final LobbyService lobbyService;

    public WebSocketEventListener(WebSocketSessionService sessionService, UserService userService, LobbyService lobbyService) {
        this.sessionService = sessionService;
        this.userService = userService;
        this.lobbyService = lobbyService;
    }


    @EventListener
    @Transactional 
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Long userId = sessionService.getUserId(sessionId);

        if (userId != null) {
            // 1. Remove from Lobby (Clears the Foreign Key constraint)
            lobbyService.handlePlayerDisconnect(userId);

            // 2. Now it is safe to delete the User
            userService.deleteUser(userId);

            sessionService.remove(sessionId);
            System.out.println("User with ID " + userId + " disconnected and was removed from the system.");
        }
    }
}