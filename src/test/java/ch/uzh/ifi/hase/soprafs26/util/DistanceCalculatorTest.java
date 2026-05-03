package ch.uzh.ifi.hase.soprafs26.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for DistanceCalculator (US7 #103).
 *
 * Two tests: one edge case (zero distance) and one real-world reference
 * (Zurich → Paris ≈ 488 km). Together they confirm both that the formula
 * handles the trivial input correctly and that it produces the expected
 * great-circle distance for a known pair of cities.
 */
public class DistanceCalculatorTest {

    /**
     * US7 #103 — Edge: distance between identical points is exactly zero.
     */
    @Test
    public void calculateDistanceInKm_samePoint_isZero() {
        double d = DistanceCalculator.calculateDistanceInKm(
                47.3769, 8.5417,
                47.3769, 8.5417);
        assertEquals(0.0, d, 1e-9, "distance from a point to itself must be exactly zero");
    }

    /**
     * US7 #103 — Real-world reference: Zurich → Paris is ~488 km.
     */
    @Test
    public void calculateDistanceInKm_zurichToParis_matchesKnownDistance() {
        // Zurich HB: 47.3769, 8.5417 — Paris (Notre-Dame): 48.8566, 2.3522
        double d = DistanceCalculator.calculateDistanceInKm(
                47.3769, 8.5417,
                48.8566, 2.3522);
        assertEquals(488.0, d, 5.0,
                "Zurich to Paris should be ~488 km (great-circle), got: " + d);
    }
}
