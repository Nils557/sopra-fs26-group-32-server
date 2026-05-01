package ch.uzh.ifi.hase.soprafs26.service;

import ch.uzh.ifi.hase.soprafs26.constant.ScoreResult;
import ch.uzh.ifi.hase.soprafs26.util.DistanceCalculator;
import org.springframework.stereotype.Service;

@Service
public class ScoringService {

    private static final double CITY_THRESHOLD_KM = 50.0;
    private static final double COUNTRY_THRESHOLD_KM = 1000.0;
    private static final int MAX_POINTS = 2000;
    private static final int HALF_POINTS = 1000;

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
}