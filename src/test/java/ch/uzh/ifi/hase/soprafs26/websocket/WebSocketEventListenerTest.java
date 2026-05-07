package ch.uzh.ifi.hase.soprafs26.websocket;

import ch.uzh.ifi.hase.soprafs26.entity.Round;
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

    /**
     * US11 #200 — Disconnect of an unsubmitted player triggers the
     * early-round-end check.
     *
     * Scenario this protects: a 3-player lobby is mid-round. Players
     * A and B have already submitted their pins. Player C drops their
     * connection (closed tab, lost wifi, browser crash). Without
     * this trigger, the remaining 30+ seconds of the round timer
     * would tick down with the surviving players waiting on a player
     * who is never coming back. The trigger asks RoundService to
     * re-evaluate whether the answers from the remaining online
     * players are now sufficient to end the round early — preserving
     * game flow.
     *
     * What this test pins: the cross-component event chain. When the
     * WebSocket session disconnects AND there is an active (non-null,
     * non-finished) round in the lobby, the listener MUST call
     *     roundService.checkAndHandleEarlyRoundEnd(lobbyCode, currentRound)
     * The listener does not decide whether the round actually ends —
     * it just asks RoundService to evaluate. RoundService's own
     * decision logic (answeredPlayers >= totalPlayers) has its own
     * tests in RoundServiceTest.
     *
     * Companion test (already exists in this file):
     *     handleWebSocketDisconnectListener_removesSessionFromSessionService
     * — covers the negative case where roundRepository returns null
     * (no active round → no early-end trigger). Together with this
     * new test, both branches of the production guard
     *     if (currentRound != null && !currentRound.isFinished())
     * are pinned.
     *
     * MANUAL SABOTAGE A (inverted guard): In WebSocketEventListener.java
     * line 57, change
     *     if (currentRound != null && !currentRound.isFinished()) {
     * to
     *     if (currentRound == null || currentRound.isFinished()) {
     * (logically inverted). With a non-null active round the body is
     * now skipped, checkAndHandleEarlyRoundEnd is not called, and
     * Mockito.verify(roundService).checkAndHandleEarlyRoundEnd(...)
     * fails with a Wanted-But-Not-Invoked error.
     *
     * MANUAL SABOTAGE B (delete the call): In WebSocketEventListener.java
     * line 59, comment out
     *     roundService.checkAndHandleEarlyRoundEnd(lobbyCode, currentRound);
     * The verify fails for the same reason as A. This sabotage is
     * useful in the demo because it removes the line the dev task is
     * literally about — proving the test pins the dev-task contract,
     * not just some adjacent behavior.
     */
    @Test
    public void handleDisconnect_activeRoundExists_triggersCheckAndHandleEarlyRoundEnd() {
        // 1. Standard SessionDisconnectEvent setup, identical to the existing test
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId("session-42");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionDisconnectEvent event = new SessionDisconnectEvent(this, message, "session-42", CloseStatus.NORMAL);

        // 2. Stub the session service so the listener proceeds past the null guards
        Mockito.when(sessionService.getUserId("session-42")).thenReturn(1L);
        Mockito.when(sessionService.getLobbyCodeBySession("session-42")).thenReturn("AB-1234");

        // 3. KEY DIFFERENCE vs the existing test: the repository returns a
        //    real, non-finished round, exercising the trigger branch
        Round activeRound = new Round();
        activeRound.setId(10L);
        activeRound.setLobbyCode("AB-1234");
        activeRound.setFinished(false);
        Mockito.when(roundRepository.findTopByLobbyCodeOrderByIdDesc("AB-1234"))
                .thenReturn(activeRound);

        // 4. Act
        listener.handleWebSocketDisconnectListener(event);

        // 5. The cross-component trigger fired with the right args
        Mockito.verify(roundService).checkAndHandleEarlyRoundEnd("AB-1234", activeRound);

        // 6. Side-effect cleanup still happened (proves we didn't break the existing path)
        Mockito.verify(sessionService).remove("session-42");
        Mockito.verify(lobbyService).handleDisconnect("AB-1234", 1L);
    }
}
