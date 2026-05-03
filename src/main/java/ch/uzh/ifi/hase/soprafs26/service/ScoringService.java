package ch.uzh.ifi.hase.soprafs26.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ch.uzh.ifi.hase.soprafs26.constant.ScoreResult;
import ch.uzh.ifi.hase.soprafs26.entity.Answer;
import ch.uzh.ifi.hase.soprafs26.entity.Lobby;
import ch.uzh.ifi.hase.soprafs26.repository.AnswerRepository;
import ch.uzh.ifi.hase.soprafs26.repository.LobbyRepository;
import ch.uzh.ifi.hase.soprafs26.util.DistanceCalculator;

@Service
public class ScoringService {

    private static final double CITY_THRESHOLD_KM = 50.0;
    private static final double COUNTRY_THRESHOLD_KM = 1000.0;
    private static final int MAX_POINTS = 2000;
    private static final int HALF_POINTS = 1000;
    private final AnswerRepository answerRepository;
    private final LobbyRepository lobbyRepository;
  
    @Autowired
    public ScoringService(AnswerRepository answerRepository, LobbyRepository lobbyRepository) {
        this.answerRepository = answerRepository;
        this.lobbyRepository = lobbyRepository;
    }

    public int calculateScore(double guessLat, double guessLng, double targetLat, double targetLng) {
        double distance = DistanceCalculator.calculateDistanceInKm(guessLat, guessLng, targetLat, targetLng);

        if (distance <= CITY_THRESHOLD_KM) {
            double penalty = (distance / CITY_THRESHOLD_KM) * 500;
            return MAX_POINTS - (int) penalty;

        } else if (distance <= COUNTRY_THRESHOLD_KM) {
            double distanceIntoCountry = distance - CITY_THRESHOLD_KM;
            double countryRange = COUNTRY_THRESHOLD_KM - CITY_THRESHOLD_KM;
            double penalty = (distanceIntoCountry / countryRange) * HALF_POINTS;
            return Math.max(0, HALF_POINTS - (int) penalty);

        } else {
            return 0;
        }
    }

    public ScoreResult getScoreResult(double guessLat, double guessLng, double targetLat, double targetLng) {
        double distance = DistanceCalculator.calculateDistanceInKm(guessLat, guessLng, targetLat, targetLng);

        if (distance <= CITY_THRESHOLD_KM) {
            return ScoreResult.CORRECT_CITY;
        } else if (distance <= COUNTRY_THRESHOLD_KM) {
            return ScoreResult.CORRECT_COUNTRY;
        } else {
            return ScoreResult.INCORRECT;
        }
    }

    @Transactional
    public List<PlayerStanding> getStandings(String lobbyCode) {
        Lobby lobby = lobbyRepository.findByLobbyCode(lobbyCode);
        if (lobby == null) return List.of();

        return lobby.getPlayers().stream()
            .map(p -> {
                int totalScore = answerRepository.findByPlayerIdAndRound_LobbyCode(p.getId(), lobbyCode).stream()
                    .mapToInt(Answer::getPointsAwarded)
                    .sum();
                return new PlayerStanding(p.getId(), p.getUsername(), totalScore);
            })
            .sorted((a, b) -> b.totalScore - a.totalScore)
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
}