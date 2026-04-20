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

import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Random;

@Service
@Transactional
public class RoundService {

    private final RoundRepository roundRepository;
    private final MapillaryService mapillaryService;
    private final Random random;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, ScheduledFuture<?>> activeTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final LobbyRepository lobbyRepository;

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
    public RoundService(RoundRepository roundRepository, MapillaryService mapillaryService, SimpMessagingTemplate messagingTemplate, LobbyRepository lobbyRepository) {
        this.roundRepository = roundRepository;
        this.mapillaryService = mapillaryService;
        this.messagingTemplate = messagingTemplate;
        this.lobbyRepository = lobbyRepository;
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
            Round round = tryCreateRound(lobbyCode, selectedLocation.getLatitude(), selectedLocation.getLongitude(), 0.01);
            if (round != null) {
                System.out.println("Game started in: " + selectedLocation.getName());
                return round;
            }
            
            System.out.println("Attempt " + attempt + " failed for " + selectedLocation.getName() + ". Retrying...");
        }

        // --------------------------------------------------------
        // FINAL FAIL-SAFE: The "Zurich Fallback"
        // --------------------------------------------------------
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

    public Round startRoundWithTimer(String lobbyCode) {
    Round round = createAndStartRound(lobbyCode);
    List<String> images = round.getImageSequence();

    Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
    int totalRounds = lobby != null ? lobby.getTotalRounds() : 1;
    int roundNumber = roundRepository.findByLobbyCode(lobbyCode).size();

    // Send first image immediately
    messagingTemplate.convertAndSend(
        "/topic/game/" + lobbyCode + "/image",
        new ImageBroadcastMessage(images.get(0), 0, roundNumber, totalRounds, 45)
    );

    // Schedule remaining images every 9 seconds
    final int[] index = {1};
    ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
    if (index[0] < images.size()) {
        broadcastImage(lobbyCode, images.get(index[0]), index[0], roundNumber, totalRounds);
        index[0]++;
    } else {
        stopTimer(lobbyCode);
    }
}, 9, 9, TimeUnit.SECONDS);

    activeTimers.put(lobbyCode, future);
    return round;
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
            roundRepository.deleteAll(rounds);
        }
    }
    public void broadcastImage(String lobbyCode, String imageUrl, int index, int roundNumber, int totalRounds) {
    messagingTemplate.convertAndSend(
        "/topic/game/" + lobbyCode + "/image",
        new ImageBroadcastMessage(imageUrl, index, roundNumber, totalRounds, 45)
    );
    }
    
    public static class ImageBroadcastMessage {
        public final String imageUrl;
        public final int index;
        public final int roundNumber;
        public final int totalRounds;
        public final int timeLeft;

        public ImageBroadcastMessage(String imageUrl, int index, int roundNumber, int totalRounds, int timeLeft) {
            this.imageUrl = imageUrl;
            this.index = index;
            this.roundNumber = roundNumber;
            this.totalRounds = totalRounds;
            this.timeLeft = timeLeft;
        }
    }

}

