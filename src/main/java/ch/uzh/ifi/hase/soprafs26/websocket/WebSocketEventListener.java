package ch.uzh.ifi.hase.soprafs26.websocket; 

import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionService;
import ch.uzh.ifi.hase.soprafs26.service.UserService;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;    
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WebSocketEventListener {

    private final WebSocketSessionService sessionService;
    private final LobbyService lobbyService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketEventListener(
            WebSocketSessionService sessionService, 
            LobbyService lobbyService, 
            UserService userService, 
            SimpMessagingTemplate messagingTemplate) {
        this.sessionService = sessionService;
        this.lobbyService = lobbyService;
        this.userService = userService;
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    @Transactional 
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Long userId = sessionService.getUserId(sessionId);

        if (userId != null) {
            lobbyService.handlePlayerDisconnect(userId);
            userService.deleteUser(userId);
            sessionService.remove(sessionId);
            messagingTemplate.convertAndSend("/topic/lobby/updates", "PLAYER_LEFT");
            System.out.println("User with ID " + userId + " disconnected and was removed from the lobby.");
        } else {
        System.out.println("ERROR: No User ID found in SessionService for " + sessionId);
    }
    }
}