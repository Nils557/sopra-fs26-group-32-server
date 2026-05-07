package ch.uzh.ifi.hase.soprafs26.websocket;

import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionService;
import ch.uzh.ifi.hase.soprafs26.repository.RoundRepository;
import ch.uzh.ifi.hase.soprafs26.service.RoundService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);
    
    // 1. Declare your dependencies once, nicely named with lowercase first letters
    private final WebSocketSessionService webSocketSessionService;
    private final RoundRepository roundRepository;
    private final RoundService roundService;
    private final LobbyService lobbyService;

    // 2. Inject ALL of them via the constructor
    public WebSocketEventListener(
            WebSocketSessionService webSocketSessionService,
            RoundRepository roundRepository,
            RoundService roundService,
            LobbyService lobbyService) {
        this.webSocketSessionService = webSocketSessionService;
        this.roundRepository = roundRepository;
        this.roundService = roundService;   
        this.lobbyService = lobbyService;
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();

        // 3. Call the methods on your properly injected instance
        Long userId = webSocketSessionService.getUserId(sessionId); // Ensure this matches your actual method name (maybe getUserIdBySession?)
        String lobbyCode = webSocketSessionService.getLobbyCodeBySession(sessionId);

        if (userId != null && lobbyCode != null) {
            log.info("User {} disconnected from Lobby {}", userId, lobbyCode);

            // Remove the player from the lobby
            lobbyService.handleDisconnect(lobbyCode, userId);

            // Find the current active round for this lobby
            Round currentRound = roundRepository.findTopByLobbyCodeOrderByIdDesc(lobbyCode);

            if (currentRound != null && !currentRound.isFinished()) {
                // Trigger the check! 
                roundService.checkAndHandleEarlyRoundEnd(lobbyCode, currentRound);
            }

            // Cleanup the session tracker
            webSocketSessionService.remove(sessionId); // Ensure this matches your actual method name (maybe removeSession?)
        }
    }
}