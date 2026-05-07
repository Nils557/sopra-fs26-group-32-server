package ch.uzh.ifi.hase.soprafs26.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import ch.uzh.ifi.hase.soprafs26.rest.dto.SubmissionUpdateDTO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for GameEventService.
 *
 * GameEventService is the single point in the backend that knows the
 * STOMP destination strings for game-time broadcasts. These tests pin
 * the exact wire format (destination + payload type + payload field
 * values) so a refactor cannot silently break the contract that the
 * frontend's STOMP subscriptions depend on.
 */
public class GameEventServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private GameEventService gameEventService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        gameEventService = new GameEventService(messagingTemplate);
    }

    /**
     * US11 #198 — Live "player submitted" ping for the round-pace UI.
     *
     * When a player submits a pin, the backend must immediately
     * broadcast a SubmissionUpdateDTO carrying that player's id on
     * /topic/lobbies/{lobbyCode}/submissions. The frontend round
     * page subscribes to this topic and uses each ping to flip the
     * sender's avatar background to green, so the rest of the lobby
     * can see the round's pace in real time.
     *
     * Two halves of the spec contract are pinned by this test:
     *   1. The destination string. A typo here would silently break
     *      the frontend's subscription — no error, no retry, just a
     *      stale UI for the rest of the lobby.
     *   2. The payload. Without playerId on the wire, the frontend
     *      can't tell which avatar to flip green.
     *
     * MANUAL SABOTAGE A (destination): In GameEventService.java line
     * 23, change the destination prefix from
     *     "/topic/lobbies/" + lobbyCode + "/submissions"
     * to (singular "lobby", matching the OTHER broadcasts in the same
     * class — a tempting "consistency fix")
     *     "/topic/lobby/" + lobbyCode + "/submissions"
     * The frontend subscribes to the plural form, so this typo would
     * silently break the live ping in production. Mockito's
     * eq("/topic/lobbies/AB-1234/submissions") matcher fails because
     * the actual call carries the singular form, and the test fails
     * with a Wanted-But-Not-Invoked error.
     *
     * MANUAL SABOTAGE B (payload): In GameEventService.java line 22,
     * change
     *     SubmissionUpdateDTO update = new SubmissionUpdateDTO(playerId);
     * to
     *     SubmissionUpdateDTO update = new SubmissionUpdateDTO();
     * The no-arg ctor leaves playerId null. The assertion
     *     dto.getPlayerId() == 7L
     * fails because the captured payload's id is null.
     */
    @Test
    public void broadcastPlayerSubmitted_sendsSubmissionUpdateDTOToSubmissionsTopic() {
        // when — player 7 submits in lobby AB-1234
        gameEventService.broadcastPlayerSubmitted("AB-1234", 7L);

        // then — convertAndSend was called with the spec-mandated destination
        // and a SubmissionUpdateDTO carrying the right player id
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/lobbies/AB-1234/submissions"),
                payloadCaptor.capture());

        Object payload = payloadCaptor.getValue();
        assertTrue(payload instanceof SubmissionUpdateDTO,
                "payload must be a SubmissionUpdateDTO, got: " + payload.getClass().getName());

        SubmissionUpdateDTO dto = (SubmissionUpdateDTO) payload;
        assertEquals(7L, dto.getPlayerId(), "payload must carry the submitting player's id");
        assertTrue(dto.isHasSubmitted(),
                "hasSubmitted must be true (the only situation we send this DTO is on submit)");
    }
}
