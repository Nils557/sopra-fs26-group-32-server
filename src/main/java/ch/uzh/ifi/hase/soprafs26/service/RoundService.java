package ch.uzh.ifi.hase.soprafs26.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.uzh.ifi.hase.soprafs26.constant.LobbyStatus;
import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.entity.User;
import ch.uzh.ifi.hase.soprafs26.repository.AnswerRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.repository.RoundRepository;
import ch.uzh.ifi.hase.soprafs26.repository.UserRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LocationResult;
import ch.uzh.ifi.hase.soprafs26.rest.dto.RoundSummaryGetDTO;
import ch.uzh.ifi.hase.soprafs26.rest.mapper.DTOMapper;
import jakarta.annotation.PostConstruct;

@Service
@Transactional
public class RoundService {

    private static final String GAME_TOPIC_PREFIX = "/topic/game/";

    private final RoundRepository roundRepository;
    private final MapillaryService mapillaryService;
    private final Random random;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, ScheduledFuture<?>> activeTimers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> activeCountdownTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final LobbyRepository lobbyRepository;
    private final UserRepository userRepository;
    private final AnswerRepository answerRepository;
    private final ScoringService scoringService;
    private final Map<String, Round> preloadedRounds = new ConcurrentHashMap<>();
    private final Logger log = LoggerFactory.getLogger(RoundService.class);
    private final GeocodingService geocodingService;
    private final GameEventService gameEventService;



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

    public RoundService(
            RoundRepository roundRepository, 
            MapillaryService mapillaryService, 
            SimpMessagingTemplate messagingTemplate, 
            LobbyRepository lobbyRepository, 
            UserRepository userRepository,
            AnswerRepository answerRepository, 
            ScoringService scoringService,
            GeocodingService geocodingService,
            GameEventService gameEventService 
        ) {
            this.roundRepository = roundRepository;
            this.mapillaryService = mapillaryService;
            this.messagingTemplate = messagingTemplate;
            this.lobbyRepository = lobbyRepository;
            this.userRepository = userRepository; 
            this.answerRepository = answerRepository;
            this.scoringService = scoringService;
            this.geocodingService = geocodingService; 
            this.gameEventService = gameEventService;
            this.random = new Random();
        }

    // This runs automatically exactly once when Spring Boot starts up
    @PostConstruct
    public void loadLocationsDataset() {
        try {
            // Read the JSON file from the resources folder
            InputStream inputStream = new ClassPathResource("locations.json").getInputStream();
            locationsDataset = objectMapper.readValue(inputStream, new TypeReference<List<CuratedLocation>>(){});
            log.info("SUCCESS: Loaded {} verified global locations into memory!", locationsDataset.size());
        } catch (IOException e) {
            log.error("FAILED to load locations.json", e);
            // Fallback just in case the file is deleted or unreadable
            locationsDataset.add(new CuratedLocation(47.3769, 8.5417, "Zurich, Switzerland"));
        }
    }

    public void preloadNextRoundAsync(String lobbyCode) {
        scheduler.execute(() -> {
            try {
                // We do exactly what createAndStartRound does, but we DON'T save it yet.
                CuratedLocation selectedLocation = locationsDataset.get(random.nextInt(locationsDataset.size()));
                
                List<String> imageUrls = mapillaryService.getImageSequence(
                    selectedLocation.getLongitude() - 0.01, selectedLocation.getLatitude() - 0.01,
                    selectedLocation.getLongitude() + 0.01, selectedLocation.getLatitude() + 0.01,
                    5
                );

                LocationResult location = geocodingService.resolveLocation(
                    selectedLocation.getLatitude(), selectedLocation.getLongitude()
                );

                Round preloaded = new Round();
                preloaded.setLobbyCode(lobbyCode);
                preloaded.setTargetLatitude(selectedLocation.getLatitude());
                preloaded.setTargetLongitude(selectedLocation.getLongitude());
                preloaded.setImageSequence(imageUrls);
                preloaded.setTargetCity(location.getCity());
                preloaded.setTargetCountry(location.getCountry());

                preloadedRounds.put(lobbyCode, preloaded);
                
                log.info("PRELOAD SUCCESS: Images for lobby {} successfully buffered in memory.", lobbyCode);

            } catch (Exception e) {
                log.error("PRELOAD FAILED for lobby {}. Will fallback to synchronous fetch. Reason: {}", lobbyCode, e.getMessage());
            }
        });
    }

    public Round resolveRoundData(String lobbyCode) {

        // 1. Check if we have a round pre-fetched in memory
        Round readyRound = preloadedRounds.remove(lobbyCode);

        // 2. If we do, just save it and return instantly
        if (readyRound != null) {
            log.info("Using preloaded round for instant start.");
            return roundRepository.save(readyRound);
        }

        if (locationsDataset.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No locations available to start the round.");
        }

        int maxAttempts = 10;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            CuratedLocation selectedLocation = locationsDataset.get(random.nextInt(locationsDataset.size()));
            
            try { 
                // Try our luck with a random city
                Round round = buildRoundFromCoordinates(lobbyCode, selectedLocation.getLatitude(), selectedLocation.getLongitude(), 0.002);
                
                // If the line above doesn't throw an error, it succeeded!
                log.info("Game started in: {}", selectedLocation.getName());
                return round;

            } catch (Exception e) {
                // If tryCreateRound fails, it jumps directly here
                String apiError = e.getMessage();
                log.info("Attempt {} failed for {}. API Reason: {} | Retrying...", attempt, selectedLocation.getName(), apiError);
            }
        }

        log.info("All random attempts failed. Triggering Zurich Fallback...");
        
        try {
            // Zurich HB coordinates are guaranteed to have thousands of Mapillary images
            Round fallbackRound = buildRoundFromCoordinates(lobbyCode, 47.3769, 8.5417, 0.001);
            log.info("Fail-safe successful: Game started in Zurich.");
            return fallbackRound;

        } catch (Exception e) {
            // If even Zurich fails, we finally stop the code and tell the frontend.
            String finalError = e.getMessage();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, 
                "Mapillary API is unreachable or returned no images. Final API error: " + finalError);
        }
    }

    /**
     * Helper method to reduce code duplication
     */
    private Round buildRoundFromCoordinates(String lobbyCode, double lat, double lon, double delta) {
        List<String> imageUrls = mapillaryService.getImageSequence(lon - delta, lat - delta, lon + delta, lat + delta, 5);
        
        // Resolve target names once here
        LocationResult location = geocodingService.resolveLocation(lat, lon);

        Round round = new Round();
        round.setLobbyCode(lobbyCode);
        round.setTargetLatitude(lat);
        round.setTargetLongitude(lon);
        round.setImageSequence(imageUrls);
        round.setTargetCity(location.getCity()); 
        round.setTargetCountry(location.getCountry());
        
        return roundRepository.save(round);
    }

    public void startRoundAsync(String lobbyCode) {
        scheduler.execute(() -> {
            try {
                executeRoundGameplay(lobbyCode);
            } catch (Exception e) {
                log.error("Round bootstrap failed for lobby {}: {}", lobbyCode, e.getMessage());
            }
        });
    }

    public Round executeRoundGameplay(String lobbyCode) {
        Round initialRound = resolveRoundData(lobbyCode);

        // mark round as started
        initialRound.setStartedAt(java.time.Instant.now());
        Round round = roundRepository.save(initialRound); 
        
        List<String> images = round.getImageSequence();

        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
        int totalRounds = lobby != null ? lobby.getTotalRounds() : 1;
        int roundNumber = roundRepository.findByLobbyCode(lobbyCode).size();

        // Send first image immediately
        messagingTemplate.convertAndSend(
            GAME_TOPIC_PREFIX + lobbyCode + "/image",
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
        
        // Only preload Image URL's if we actually have more rounds left to play
        if (roundNumber < totalRounds) {
            log.info("GAMEPLAY: Round {} started. Triggering background preload for Round {} in lobby {}", 
                     roundNumber, roundNumber + 1, lobbyCode);
            preloadNextRoundAsync(lobbyCode);
        }
        return round;
    }

    private void startCountdownTimer(String lobbyCode, Round round) {
        final int[] timeLeft = {45};
    
        ScheduledFuture<?> countdownFuture = scheduler.scheduleAtFixedRate(() -> {
            timeLeft[0]--;
            
            //#112 — Broadcast timer tick
            messagingTemplate.convertAndSend(
                GAME_TOPIC_PREFIX + lobbyCode + "/timer",
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
                log.info("Countdown timer stopped for lobby: {}", lobbyCode);
        }
    }

    public void stopTimer(String lobbyCode) {
        ScheduledFuture<?> future = activeTimers.remove(lobbyCode);
            if (future != null) {
                future.cancel(false);
                log.info("Timer stopped for lobby: {}", lobbyCode);
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
            GAME_TOPIC_PREFIX + lobbyCode + "/image",
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

    public static class GameEndMessage {
        public static final String EVENT = "GAME_END";
        public final List<ScoringService.FinalStanding> standings;
        public GameEndMessage(List<ScoringService.FinalStanding> standings) {
            this.standings = standings;
        }
    }

    public Answer submitAnswer(String lobbyCode, Long roundId, Long playerId, Double latitude, Double longitude) {
        // ==========================================
        // 1. VALIDATION
        // ==========================================
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

        User player = userRepository.findById(playerId) // Fixed capitalization
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found"));

        if (answerRepository.existsByRoundIdAndPlayerId(roundId, playerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Player has already submitted an answer");
        }

        // ==========================================
        // 2. PERSISTENCE
        // ==========================================
        Answer answer = new Answer();
        answer.setLatitude(latitude);
        answer.setLongitude(longitude);
        answer.setRound(round);
        answer.setPlayer(player);
        answer.setSubmittedAt(Instant.now());
        answer.setPointsAwarded(0); 
        
        answer.calculateScoreBasedOnDistance(scoringService);
        answer = answerRepository.save(answer);

        // ==========================================
        // 3. BROADCAST EVENTS (Delegated to GameEventService)
        // ==========================================
        // A. Live action ping for the UI (Task #198)
        gameEventService.broadcastPlayerSubmitted(lobbyCode, player.getId());

        // B. Fetch updated data for bulk broadcasts
        List<Answer> roundAnswers = answerRepository.findByRoundId(roundId);
        
        List<AnswerState> states = roundAnswers.stream()
            .map(a -> new AnswerState(a.getPlayer().getId(), a.getPlayer().getUsername(), true))
            .toList();
            
        gameEventService.broadcastAnswerStates(lobbyCode, states);
        gameEventService.broadcastScores(lobbyCode, scoringService.getStandings(lobbyCode));

        // ==========================================
        // 4. GAME STATE MANAGEMENT
        // ==========================================
        checkAndHandleEarlyRoundEnd(lobbyCode, round);

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
            GAME_TOPIC_PREFIX + lobbyCode + "/roundEnd",
            "ROUND_ENDED"
        );
        try {
            RoundSummaryGetDTO summaryPayload = getRoundSummary(lobbyCode, round.getId());
            messagingTemplate.convertAndSend(GAME_TOPIC_PREFIX + lobbyCode + "/summary", summaryPayload);
            log.info("Successfully broadcasted summary screen payload.");

            Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
            if (lobby != null) {
                int currentRoundNumber = roundRepository.findByLobbyCode(lobbyCode).size();
                int totalRounds = lobby.getTotalRounds();
                
                log.info("Scheduling next action in 10s. Current Round: {}, Total Rounds: {}", currentRoundNumber, totalRounds);

                scheduler.schedule(() -> {
                    try {
                        if (currentRoundNumber >= totalRounds) {
                            lobby.setStatus(LobbyStatus.FINISHED);
                            lobbyRepository.save(lobby);
                            List<ScoringService.FinalStanding> finalStandings = scoringService.getFinalStandings(lobbyCode);
                            messagingTemplate.convertAndSend(GAME_TOPIC_PREFIX + lobbyCode + "/game-state", new GameEndMessage(finalStandings));
                            preloadedRounds.remove(lobbyCode);
                            log.info("Broadcasted GAME_END with final standings for lobby: {}", lobbyCode);
                        } else {
                            messagingTemplate.convertAndSend(GAME_TOPIC_PREFIX + lobbyCode + "/game-state", "NEXT_ROUND");
                            log.info("Broadcasted NEXT_ROUND for lobby: {}", lobbyCode);
                            executeRoundGameplay(lobbyCode); // Start Round 2!
                        }
                    } catch (MessagingException e) {
                        log.error("CRASH INSIDE SCHEDULER: ", e); // Catch errors inside the timer
                    }
                }, 10, TimeUnit.SECONDS);
            } else {
                log.error("Lobby was NULL when trying to schedule the next round!");
            }
        } catch (MessagingException e) {
            log.error("CRITICAL CRASH DURING ROUND END TRANSITION: ", e); // Catch the silent killer!
        }
    }

    public RoundSummaryGetDTO getRoundSummary(String lobbyCode, Long roundId) {
        Round round = roundRepository.findById(roundId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Round not found"));

        if (!round.getLobbyCode().equals(lobbyCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Round does not belong to this lobby");
        }

        // -----------------------------------------------------------------
        // TASK #199: Fetch answers and extract player IDs for the frontend
        // -----------------------------------------------------------------
        List<Answer> answers = answerRepository.findByRoundId(roundId);
        
        List<Long> submittedIds = answers.stream()
            .map(answer -> answer.getPlayer().getId())
            .toList();
            
        // Set the transient field so DTOMapper can automatically pick it up
        round.setSubmittedPlayerIds(submittedIds);
        // -----------------------------------------------------------------

        List<ScoringService.PlayerStanding> standings = scoringService.getStandings(lobbyCode);
        
        return DTOMapper.INSTANCE.convertToRoundSummaryGetDTO(round, standings);
    }

    public void checkAndHandleEarlyRoundEnd(String lobbyCode, Round currentRound) {
        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
        if (lobby == null || currentRound == null) return;

        int totalPlayers = lobby.getPlayers().size(); // Now updated if someone left
        int answeredPlayers = answerRepository.findByRoundId(currentRound.getId()).size();

        if (totalPlayers > 0 && answeredPlayers >= totalPlayers) {
            log.info("All active players answered (or remaining players after a disconnect). Ending round early for lobby: {}", lobbyCode);
            handleRoundEnd(lobbyCode, currentRound);
        }
    }
}
