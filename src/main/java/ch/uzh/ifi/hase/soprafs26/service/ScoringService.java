package ch.uzh.ifi.hase.soprafs26.service;

import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Service;

import ch.uzh.ifi.hase.soprafs26.constant.ScoreResult;
import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.entity.Round;
import ch.uzh.ifi.hase.soprafs26.repository.AnswerRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.rest.dto.LocationResult;
import ch.uzh.ifi.hase.soprafs26.util.DistanceCalculator;

@Service
public class ScoringService {
    private static final int MAX_POINTS = 2000;
    private static final int COUNTRY_PENALTY_THRESHOLD = 1000;
    private static final long MAX_ROUND_TIME_SECONDS = 45;

    private final AnswerRepository answerRepository;
    private final LobbyRepository lobbyRepository;
    private final GeocodingService geocodingService;

    public ScoringService(AnswerRepository answerRepository, LobbyRepository lobbyRepository, GeocodingService geocodingService) {
        this.answerRepository = answerRepository;
        this.lobbyRepository = lobbyRepository;
        this.geocodingService = geocodingService;
    }

    public void calculateScore(Answer answer, Round round) {
        // 1. Resolve location ONCE to save API calls
        LocationResult guessLocation = geocodingService.resolveLocation(answer.getLatitude(), answer.getLongitude());
        
        // 2. Determine the result classification
        ScoreResult result = determineScoreResult(guessLocation, round);
        answer.setScoreResult(result);

        // 3. Calculate the base score (Distance/Location)
        int basePoints = calculateBaseScore(answer.getLatitude(), answer.getLongitude(), guessLocation, round);

        // 4. Apply the Time Decay Multiplier
        int finalPoints = applyTimeDecayMultiplier(basePoints, result, round, answer);
        
        // 5. Save the final calculated points to the Answer entity
        answer.setPointsAwarded(finalPoints);
    }

    // ------------------------HELPER METHODS FOR SCORE CALCULATION------------------------

    private ScoreResult determineScoreResult(LocationResult guessLocation, Round round) {
        if (guessLocation.getCity().equalsIgnoreCase(round.getTargetCity())) {
            return ScoreResult.CORRECT_CITY;
        } else if (guessLocation.getCountry().equalsIgnoreCase(round.getTargetCountry())) {
            return ScoreResult.CORRECT_COUNTRY;
        }
        return ScoreResult.INCORRECT;
    }

    private int calculateBaseScore(double guessLat, double guessLng, LocationResult guessLocation, Round round) {
        double distance = DistanceCalculator.calculateDistanceInKm(guessLat, guessLng, round.getTargetLatitude(), round.getTargetLongitude());

        // 1. CITY MATCH: Max Points
        if (guessLocation.getCity().equalsIgnoreCase(round.getTargetCity())) {
            return MAX_POINTS;
        }

        // 2. COUNTRY MATCH: Partial Points (1000 - 1999 range)
        if (guessLocation.getCountry().equalsIgnoreCase(round.getTargetCountry())) {
            return (int) Math.max(COUNTRY_PENALTY_THRESHOLD, (MAX_POINTS - 1) - (distance * 0.5));
        }

        // 3. WRONG COUNTRY, BUT WITHIN 1000KM THRESHOLD (0 - 999 range)
        if (distance <= 1000.0) {
            int distanceScore = (int) Math.max(0, 1000 - distance);
            return Math.min(COUNTRY_PENALTY_THRESHOLD - 1, distanceScore);
        }

        // 4. INCORRECT COUNTRY AND TOO FAR AWAY (> 1000km)
        return 0;
    }

    private int applyTimeDecayMultiplier(int basePoints, ScoreResult result, Round round, Answer answer) {
        // Only reward speed bonuses for actually guessing the right city or country!
        if (result == ScoreResult.INCORRECT || basePoints == 0) {
            return basePoints; 
        }

        // Calculate time taken using the entity timestamps
        long secondsTaken = Duration.between(round.getStartedAt(), answer.getSubmittedAt()).getSeconds();

        // Safety check: bound it between 0 and the max round time (prevents negative values if there's lag)
        secondsTaken = Math.max(0, Math.min(secondsTaken, MAX_ROUND_TIME_SECONDS));

        // Multiplier formula: 100% at 0 seconds, decaying linearly down to 30% at the final second
        double timeFraction = (double) secondsTaken / MAX_ROUND_TIME_SECONDS;
        double decayMultiplier = 1.0 - (0.7 * timeFraction);

        // Apply multiplier and round to the nearest integer
        return (int) Math.round(basePoints * decayMultiplier);
    }

    // ------------------------END HELPER METHODS FOR SCORE CALCULATION------------------------

    public List<PlayerStanding> getStandings(String lobbyCode) {
        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
        if (lobby == null) return List.of();

        return lobby.getPlayers().stream()
            .map(p -> {
                int totalScore = answerRepository.findByPlayerIdAndRound_LobbyCode(p.getId(), lobbyCode).stream()
                    .mapToInt(Answer::getPointsAwarded).sum();
                return new PlayerStanding(p.getId(), p.getUsername(), totalScore);
            })
            .sorted((a, b) -> Integer.compare(b.totalScore, a.totalScore))
            .toList();
    }

    public static class PlayerStanding {
        public final Long playerId;
        public final String username;
        public final int totalScore;
        public PlayerStanding(Long playerId, String username, int totalScore) {
            this.playerId = playerId;
            this.username = username;
            this.totalScore = totalScore;
        }
    }

    public static class FinalStanding {
        public final int rank;
        public final Long playerId;
        public final String username;
        public final int totalScore;

        public FinalStanding(int rank, Long playerId, String username, int totalScore) {
            this.rank = rank;
            this.playerId = playerId;
            this.username = username;
            this.totalScore = totalScore;
        }
    }

    public List<FinalStanding> getFinalStandings(String lobbyCode) {
        List<PlayerStanding> standings = getStandings(lobbyCode);
        int[] rank = {1};
        return standings.stream()
            .map(s -> new FinalStanding(rank[0]++, s.playerId, s.username, s.totalScore))
            .toList();
    }
}

