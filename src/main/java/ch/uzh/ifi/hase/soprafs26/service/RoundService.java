package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.repository.RoundRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import jakarta.annotation.PostConstruct;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import ch.uzh.ifi.hase.soprafs26.constant.LobbyStatus;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.rest.dto.AnswerPostDTO;
import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import ch.uzh.ifi.hase.soprafs26.repository.AnswerRepository;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Random;
import java.time.Instant;
import java.util.stream.Collectors;

@Service
@Transactional
public class RoundService {

    private final RoundRepository roundRepository;
    private final MapillaryService mapillaryService;
    private final Random random;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, ScheduledFuture<?>> activeTimers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> activeCountdownTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final LobbyRepository lobbyRepository;
    private final UserRepository UserRepository;
    private final AnswerRepository answerRepository;

    // This list will hold all 200+ coordinates in memory for instant access
    private List<CuratedLocation> locationsDataset = new ArrayList<>();

    // Internal helper class mapped to your JSON keys
    public static class CuratedLocation {
        private double latitude;
        private double longitude;
        private String name;

        // Default constructor required for Jackson JSON parsing
        public CuratedLocation() {}

        public CuratedLocation(double latitude, double longitude, String name) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.name = name;
        }

        // Getters and Setters required for Jackson JSON parsing
        public double getLatitude() { return latitude; }
        public void setLatitude(double latitude) { this.latitude = latitude; }
        public double getLongitude() { return longitude; }
        public void setLongitude(double longitude) { this.longitude = longitude; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Autowired
    public RoundService(RoundRepository roundRepository, MapillaryService mapillaryService, SimpMessagingTemplate messagingTemplate, LobbyRepository lobbyRepository, UserRepository UserRepository, AnswerRepository answerRepository) {
        this.roundRepository = roundRepository;
        this.mapillaryService = mapillaryService;
        this.messagingTemplate = messagingTemplate;
        this.lobbyRepository = lobbyRepository;
        this.UserRepository = UserRepository;
        this.answerRepository = answerRepository;
        this.random = new Random();
    }

    // This runs automatically exactly once when Spring Boot starts up
    @PostConstruct
    public void loadLocationsDataset() {
        try {
            // Read the JSON file from the resources folder
            InputStream inputStream = new ClassPathResource("locations.json").getInputStream();
            locationsDataset = objectMapper.readValue(inputStream, new TypeReference<List<CuratedLocation>>(){});
            System.out.println("SUCCESS: Loaded " + locationsDataset.size() + " verified global locations into memory!");
        } catch (Exception e) {
            System.err.println("FAILED to load locations.json: " + e.getMessage());
            // Fallback just in case the file is deleted or unreadable
            locationsDataset.add(new CuratedLocation(47.3769, 8.5417, "Zurich, Switzerland"));
        }
    }

public Round createAndStartRound(String lobbyCode) {
        if (locationsDataset.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No locations available to start the round.");
        }

        int maxAttempts = 10;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            CuratedLocation selectedLocation = locationsDataset.get(random.nextInt(locationsDataset.size()));
            
            // Try our luck with a random city
            Round round = tryCreateRound(lobbyCode, selectedLocation.getLatitude(), selectedLocation.getLongitude(), 0.04);
            if (round != null) {
                System.out.println("Game started in: " + selectedLocation.getName());
                return round;
            }
            
            System.out.println("Attempt " + attempt + " failed for " + selectedLocation.getName() + ". Retrying...");
        }

        System.out.println("All random attempts failed. Triggering Zurich Fallback...");
        
        // Zurich HB coordinates are guaranteed to have thousands of Mapillary images
        Round fallbackRound = tryCreateRound(lobbyCode, 47.3769, 8.5417, 0.005);
        
        if (fallbackRound != null) {
            System.out.println("Fail-safe successful: Game started in Zurich.");
            return fallbackRound;
        }

        // If even Zurich fails, the Mapillary API is likely down or the token is expired
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, 
            "Mapillary API is unreachable or returned no images even for fallback locations.");
    }

    /**
     * Helper method to reduce code duplication
     */
    private Round tryCreateRound(String lobbyCode, double lat, double lon, double delta) {
        try {
            List<String> imageUrls = mapillaryService.getImageSequence(
                    lon - delta, lat - delta, 
                    lon + delta, lat + delta, 
                    5);

            Round round = new Round();
            round.setLobbyCode(lobbyCode);
            round.setTargetLatitude(lat);
            round.setTargetLongitude(lon);
            round.setImageSequence(imageUrls);
            return roundRepository.save(round);
        } catch (Exception e) {
            return null; // Return null to signal this specific attempt failed
        }
    }

    public void startRoundWithTimerAsync(String lobbyCode) {
        scheduler.execute(() -> {
            try {
                startRoundWithTimer(lobbyCode);
            } catch (Exception e) {
                System.err.println("Round bootstrap failed for lobby " + lobbyCode + ": " + e.getMessage());
            }
        });
    }

    public Round startRoundWithTimer(String lobbyCode) {
        Round round = createAndStartRound(lobbyCode);
        List<String> images = round.getImageSequence();

        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
        int totalRounds = lobby != null ? lobby.getTotalRounds() : 1;
        int roundNumber = roundRepository.findByLobbyCode(lobbyCode).size();

        // Send first image immediately
        messagingTemplate.convertAndSend(
            "/topic/game/" + lobbyCode + "/image",
            new ImageBroadcastMessage(round.getId(), images.get(0), 0, roundNumber, totalRounds, 45)
        );

        // Schedule remaining images every 9 seconds
        final int[] index = {1};
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            if (index[0] < images.size()) {
                broadcastImage(lobbyCode, round.getId(), images.get(index[0]), index[0], roundNumber, totalRounds);
                index[0]++;
            } else {
                stopTimer(lobbyCode);
            }
        }, 9, 9, TimeUnit.SECONDS);

        activeTimers.put(lobbyCode, future);
        startCountdownTimer(lobbyCode, round);
        return round;
    }

    private void startCountdownTimer(String lobbyCode, Round round) {
        final int[] timeLeft = {45};
    
        ScheduledFuture<?> countdownFuture = scheduler.scheduleAtFixedRate(() -> {
            timeLeft[0]--;
            
            //#112 — Broadcast timer tick
            messagingTemplate.convertAndSend(
                "/topic/game/" + lobbyCode + "/timer",
                timeLeft[0]
            );
        
            //#110 — Round ends when timer reaches zero
            if (timeLeft[0] <= 0) {
                // Check latest DB state to ensure we don't double-trigger if early-end just fired
                Round currentRound = roundRepository.findById(round.getId()).orElse(round);
                if (!currentRound.isFinished()) {
                    handleRoundEnd(lobbyCode, currentRound);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    
        activeCountdownTimers.put(lobbyCode, countdownFuture);
    }

    public void stopCountdownTimer(String lobbyCode) {
        ScheduledFuture<?> future = activeCountdownTimers.remove(lobbyCode);
            if (future != null) {
                future.cancel(false);
                System.out.println("Countdown timer stopped for lobby: " + lobbyCode);
        }
    }

    public void stopTimer(String lobbyCode) {
        ScheduledFuture<?> future = activeTimers.remove(lobbyCode);
            if (future != null) {
                future.cancel(false);
                System.out.println("Timer stopped for lobby: " + lobbyCode);
            }
    }

    public void cleanupLobby(String lobbyCode) {
        List<Round> rounds = roundRepository.findByLobbyCode(lobbyCode);
        if (!rounds.isEmpty()) {
            rounds.forEach(r -> answerRepository.deleteAll(answerRepository.findByRoundId(r.getId())));
            roundRepository.deleteAll(rounds);
        }
    }
    public void broadcastImage(String lobbyCode, Long roundId, String imageUrl, int index, int roundNumber, int totalRounds) {
        messagingTemplate.convertAndSend(
            "/topic/game/" + lobbyCode + "/image",
            new ImageBroadcastMessage(roundId, imageUrl, index, roundNumber, totalRounds, 45)
        );
    }
    
    public static class ImageBroadcastMessage {
        public final String imageUrl;
        public final int index;
        public final int roundNumber;
        public final int totalRounds;
        public final int timeLeft;
        public final Long roundId;

        public ImageBroadcastMessage(Long roundId, String imageUrl, int index, int roundNumber, int totalRounds, int timeLeft) {
            this.roundId = roundId;
            this.imageUrl = imageUrl;
            this.index = index;
            this.roundNumber = roundNumber;
            this.totalRounds = totalRounds;
            this.timeLeft = timeLeft;
        }
    }

    public static class AnswerState {
        public final Long playerId;
        public final String username;
        public final Boolean answered;
        public AnswerState(Long playerId, String username, Boolean answered) {
            this.playerId = playerId;
            this.username = username;
            this.answered = answered;
        }
    }

    public Answer submitAnswer(String lobbyCode, Long roundId, Long playerId, Double latitude, Double longitude) {
        // Validate Coordinates
        if (playerId == null || latitude == null || longitude == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payload missing vital fields");
        }
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coordinates out of bounds.");
        }

        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
        if (lobby == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lobby not found");
        }

        Round round = roundRepository.findById(roundId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Round not found"));

        if (!round.getLobbyCode().equals(lobbyCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Round does not belong to this lobby");
        }

        if (round.isFinished()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Round has already finished");
        }

        User player = UserRepository.findById(playerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found"));

        boolean alreadyAnswered = answerRepository.existsByRoundIdAndPlayerId(roundId, playerId);
        if (alreadyAnswered) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Player has already submitted an answer");
        }

        // Save new answer
        Answer answer = new Answer();
        answer.setLatitude(latitude);
        answer.setLongitude(longitude);
        answer.setRound(round);
        answer.setPlayer(player);
        answer.setSubmittedAt(Instant.now());
        answer.setPointsAwarded(0); // Scored evaluated at round end (S9)
        answer.calculateScoreBasedOnDistance();
        answer = answerRepository.save(answer);

        // Notify lobby via WebSocket (Real-Time UI Update for S11)
        List<Answer> roundAnswers = answerRepository.findByRoundId(roundId);
        List<AnswerState> states = roundAnswers.stream()
            .map(a -> new AnswerState(a.getPlayer().getId(), a.getPlayer().getUsername(), true))
            .collect(Collectors.toList());
            
        messagingTemplate.convertAndSend("/topic/lobby/" + lobbyCode + "/answers", states);

        //#111 — Early round end if all players have answered
        Lobby lobby2 = lobbyRepository.findByLobbyCode(lobbyCode);
        if (lobby2 != null) {
            int totalPlayers = lobby2.getPlayers().size();
            int answeredPlayers = roundAnswers.size(); // Reuse the list we just fetched!
    
            if (answeredPlayers >= totalPlayers) {
                System.out.println("All players answered. Early round end for lobby: " + lobbyCode);
                handleRoundEnd(lobbyCode, round);
            }
        }

        return answer;
    }

    /**
     * Centralized logic to handle the end of a round, whether by timer or all players answering.
     * Evaluates if the game should progress to the next round or end entirely.
     */
    private synchronized void handleRoundEnd(String lobbyCode, Round round) {
        if (round.isFinished()) {
            return; // Prevent race conditions if timer and last answer happen at the exact same millisecond
        }

        stopCountdownTimer(lobbyCode);
        stopTimer(lobbyCode);
        round.setFinished(true);
        roundRepository.save(round);
        
        // Broadcast round end event to trigger summary screen on frontend
        messagingTemplate.convertAndSend(
            "/topic/game/" + lobbyCode + "/roundEnd",
            "ROUND_ENDED"
        );
        System.out.println("Round ended for lobby: " + lobbyCode);

        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
        if (lobby != null) {
            int currentRoundNumber = roundRepository.findByLobbyCode(lobbyCode).size();
            int totalRounds = lobby.getTotalRounds();

            // Schedule next action after a 10-second delay so players can see the round summary / results
            scheduler.schedule(() -> {
                if (currentRoundNumber >= totalRounds) {
                    // Game Over
                    lobby.setStatus(LobbyStatus.FINISHED);
                    lobbyRepository.save(lobby);
                    messagingTemplate.convertAndSend("/topic/game/" + lobbyCode + "/status", "GAME_OVER");
                    System.out.println("Game finished for lobby: " + lobbyCode);
                } else {
                    // Start Next Round
                    System.out.println("Starting next round for lobby: " + lobbyCode);
                    startRoundWithTimer(lobbyCode);
                }
            }, 4, TimeUnit.SECONDS);
        }
    }

}
