package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.rest.dto.SubmissionUpdateDTO;
import ch.uzh.ifi.hase.soprafs26.service.RoundService.AnswerState;
import ch.uzh.ifi.hase.soprafs26.service.ScoringService.PlayerStanding;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GameEventService {

    private final SimpMessagingTemplate messagingTemplate;

    public GameEventService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // Task #198 - Broadcast live submission ping
    public void broadcastPlayerSubmitted(String lobbyCode, Long playerId) {
        SubmissionUpdateDTO update = new SubmissionUpdateDTO(playerId);
        messagingTemplate.convertAndSend("/topic/lobbies/" + lobbyCode + "/submissions", update);
    }

    // Legacy/Existing - Broadcast answer states
    public void broadcastAnswerStates(String lobbyCode, List<AnswerState> states) {
        messagingTemplate.convertAndSend("/topic/lobby/" + lobbyCode + "/answers", states);
    }

    // Task #147 - Broadcast score updates
    public void broadcastScores(String lobbyCode, List<PlayerStanding> scores) {
        messagingTemplate.convertAndSend("/topic/lobby/" + lobbyCode + "/scores", scores);
    }
}