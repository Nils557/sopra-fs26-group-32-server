package ch.uzh.ifi.hase.soprafs26.websocket;

import ch.uzh.ifi.hase.soprafs26.service.WebSocketSessionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
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

    @Mock
    private WebSocketSessionService sessionService;

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
        // given — a disconnect event carrying a known session id
        SessionDisconnectEvent event = Mockito.mock(SessionDisconnectEvent.class);
        Mockito.when(event.getSessionId()).thenReturn("session-42");

        // when — Spring would call this via @EventListener; we invoke it directly
        listener.handleWebSocketDisconnectListener(event);

        // then — the session service had remove invoked with the exact session id
        Mockito.verify(sessionService).remove("session-42");
        // and nothing else (no lobby, no broadcast — that's by design)
        Mockito.verifyNoMoreInteractions(sessionService);
    }
}
