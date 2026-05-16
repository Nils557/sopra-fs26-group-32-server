package ch.uzh.ifi.hase.soprafs26.service;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);
    private static final int MAX_POINTS = 2000;
    private static final int COUNTRY_PENALTY_THRESHOLD = 1000;

    private final AnswerRepository answerRepository;
    private final LobbyRepository lobbyRepository;
    private final GeocodingService geocodingService;

    @Autowired
    public ScoringService(AnswerRepository answerRepository, LobbyRepository lobbyRepository, GeocodingService geocodingService) {
        this.answerRepository = answerRepository;
        this.lobbyRepository = lobbyRepository;
        this.geocodingService = geocodingService;
    }

    public int calculateScore(double guessLat, double guessLng, Round round) {
        double distance = DistanceCalculator.calculateDistanceInKm(guessLat, guessLng, round.getTargetLatitude(), round.getTargetLongitude());
        LocationResult guessLocation = geocodingService.resolveLocation(guessLat, guessLng);

        // 1. CITY MATCH: Max Points
        if (guessLocation.getCity().equalsIgnoreCase(round.getTargetCity())) {
            return MAX_POINTS;
        }

        // 2. COUNTRY MATCH: Partial Points (1000 - 1999 range)
        if (guessLocation.getCountry().equalsIgnoreCase(round.getTargetCountry())) {
            return (int) Math.max(COUNTRY_PENALTY_THRESHOLD, (MAX_POINTS - 1) - (distance * 0.5));
        }

        // 3. WRONG COUNTRY, BUT WITHIN 1000KM THRESHOLD with standard decay (0 - 999 range)
        if (distance <= 1000.0) {
            int distanceScore = (int) Math.max(0, 1000 - distance); 
            return Math.min(COUNTRY_PENALTY_THRESHOLD - 1, distanceScore);
        }

        // 4. INCORRECT COUNTRY AND TOO FAR AWAY (> 1000km)
        return 0;
    }

    public ScoreResult getScoreResult(double guessLat, double guessLng, Round round) {
        LocationResult guessLocation = geocodingService.resolveLocation(guessLat, guessLng);

        if (guessLocation.getCity().equalsIgnoreCase(round.getTargetCity())) {
            return ScoreResult.CORRECT_CITY;
        } else if (guessLocation.getCountry().equalsIgnoreCase(round.getTargetCountry())) {
            return ScoreResult.CORRECT_COUNTRY;
        }
        return ScoreResult.INCORRECT;
    }

    @Transactional
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
            .collect(Collectors.toList());
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
            .collect(Collectors.toList());
    }
}

