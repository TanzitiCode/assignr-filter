package util;

/**
 * Calculates straight-line distance between two GPS coordinates.
 *
 * Uses the Haversine formula — the standard algorithm for computing
 * distances on a sphere (the Earth is close enough to a sphere for this).
 *
 * WHY not use Google Maps API?
 * - Google Maps requires a billing account and can cost money at scale
 * - Haversine is accurate to within ~0.5% for distances under 100 miles
 * - This is the right MVP choice; upgrade to Google Maps later for road distance
 *
 * NOTE: This gives you "as-the-crow-flies" distance, not driving distance.
 * For a soccer ref, it's a good proxy — venues in the same area are similarly
 * close by road.
 */
public class DistanceCalculator {

    private static final double EARTH_RADIUS_MILES = 3958.8;

    /**
     * Returns the distance in miles between two lat/lon pairs.
     *
     * @param lat1  Your home latitude  (e.g., 38.9072)
     * @param lon1  Your home longitude (e.g., -77.0369)
     * @param lat2  Destination latitude
     * @param lon2  Destination longitude
     * @return Distance in miles (straight line)
     */
    public static double calculateMiles(double lat1, double lon1,
                                        double lat2, double lon2) {
        // Math.toRadians converts degrees → radians (required for trig functions in Java)
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        // Haversine formula
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1))
                 * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_MILES * c;
    }

    /**
     * Quick sanity check — are these valid GPS coordinates?
     * Latitude:  -90 to  90   Longitude: -180 to 180
     */
    public static boolean isValidCoordinate(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
               && !(lat == 0.0 && lon == 0.0); // (0,0) is in the Atlantic Ocean — almost certainly wrong
    }
}
