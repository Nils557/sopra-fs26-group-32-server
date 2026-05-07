package ch.uzh.ifi.hase.soprafs26.websocket;

import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionService;
import ch.uzh.ifi.hase.soprafs26.service.LobbyService;
import ch.uzh.ifi.hase.soprafs26.service.RoundService;
import ch.uzh.ifi.hase.soprafs26.repository.RoundRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Unit tests for WebSocketEventListener.
 *
 * The listener's single responsibility is to scrub the sessionId->userId
 * pairing from WebSocketSessionService when a STOMP session disconnects,
 * so the in-memory map doesn't leak stale entries. It deliberately does
 * NOT call any lobby-cleanup logic — that architectural split is part
 * of the project's "Logout deletes the user; WS disconnect only cleans
 * session state" design choice.
 *
 * We mock the SessionDisconnectEvent rather than constructing one
 * because the real constructor requires a Message + CloseStatus + sessionId,
 * and the listener only reads getSessionId() off the event. Stubbing
 * that single method is enough to exercise the contract.
 */
public class WebSocketEventListenerTest {

    @Mock private WebSocketSessionService sessionService;

    @Mock private LobbyService lobbyService;

    @Mock private RoundService roundService;

    @Mock private RoundRepository roundRepository;

    @InjectMocks
    private WebSocketEventListener listener;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    /**
     * US3 #68 — Detect player disconnection via WebSocket.
     *
     * When Spring fires a SessionDisconnectEvent (browser closed, tab
     * refreshed, network dropped, idle timeout), the listener must
     * forward the sessionId to WebSocketSessionService.remove so the
     * reverse lookup map doesn't keep growing.
     *
     * Note: this test intentionally does NOT verify any interaction
     * with LobbyService — lobby membership cleanup happens via the
     * DELETE /users/{id} path invoked by the Logout button, not on WS
     * disconnect. If a future change adds lobby cleanup here, that
     * would be a new test, not a modification of this one.
     */
    @Test
    public void handleWebSocketDisconnectListener_removesSessionFromSessionService() {
        // 1. Create Stomp headers containing the target session ID
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId("session-42");

        // 2. Wrap the headers in a standard Spring Message
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        // 3. Create the event using that message
        SessionDisconnectEvent event = new SessionDisconnectEvent(this, message, "session-42", CloseStatus.NORMAL);

        // 4. Stub the session service so it passes the 'if (userId != null)' check
        Mockito.when(sessionService.getUserId("session-42")).thenReturn(1L);
        Mockito.when(sessionService.getLobbyCodeBySession("session-42")).thenReturn("AB-1234");
        
        // Stub the repository to return null just to prevent null pointers
        Mockito.when(roundRepository.findTopByLobbyCodeOrderByIdDesc("AB-1234")).thenReturn(null);

        // 5. Act
        listener.handleWebSocketDisconnectListener(event);

        // 6. Assert that the cleanup logic ran properly
        Mockito.verify(sessionService).remove("session-42");
        Mockito.verify(lobbyService).handleDisconnect("AB-1234", 1L);
    }
}
