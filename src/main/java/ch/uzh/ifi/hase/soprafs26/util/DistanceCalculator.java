package ch.uzh.ifi.hase.soprafs26.util;

/**
 * Utility class to calculate geographical distances.
 */
public class DistanceCalculator {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * Calculates the distance in kilometers between two geographical points using the Haversine formula.
     * This accounts for the spherical shape of the Earth.
     *
     * @param startLat Latitude of the first point in decimal degrees (Guess)
     * @param startLng Longitude of the first point in decimal degrees (Guess)
     * @param endLat   Latitude of the second point in decimal degrees (Correct Location)
     * @param endLng   Longitude of the second point in decimal degrees (Correct Location)
     * @return Distance in kilometers
     */
    public static double calculateDistanceInKm(double startLat, double startLng, double endLat, double endLng) {
        double dLat = Math.toRadians(endLat - startLat);
        double dLng = Math.toRadians(endLng - startLng);

        double originLat = Math.toRadians(startLat);
        double targetLat = Math.toRadians(endLat);

        double a = Math.pow(Math.sin(dLat / 2), 2) 
                 + Math.cos(originLat) * Math.cos(targetLat) * Math.pow(Math.sin(dLng / 2), 2);
                 
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }
}