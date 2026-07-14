package org.firstinspires.ftc.teamcode.navigation;

/** Geographic calculations used by the point-to-point navigation data layer. */
public final class GeoNavigation {
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoNavigation() {
    }

    public static double distanceMeters(
            double fromLatitudeDegrees,
            double fromLongitudeDegrees,
            double toLatitudeDegrees,
            double toLongitudeDegrees) {
        double fromLat = Math.toRadians(fromLatitudeDegrees);
        double toLat = Math.toRadians(toLatitudeDegrees);
        double deltaLat = toLat - fromLat;
        double deltaLon = Math.toRadians(toLongitudeDegrees - fromLongitudeDegrees);

        double sinHalfLat = Math.sin(deltaLat / 2.0);
        double sinHalfLon = Math.sin(deltaLon / 2.0);
        double a = sinHalfLat * sinHalfLat
                + Math.cos(fromLat) * Math.cos(toLat) * sinHalfLon * sinHalfLon;
        double centralAngle = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1.0 - a)));
        return EARTH_RADIUS_METERS * centralAngle;
    }

    /** Returns the initial bearing in compass degrees: north=0, east=90, south=180. */
    public static double initialBearingDegrees(
            double fromLatitudeDegrees,
            double fromLongitudeDegrees,
            double toLatitudeDegrees,
            double toLongitudeDegrees) {
        double fromLat = Math.toRadians(fromLatitudeDegrees);
        double toLat = Math.toRadians(toLatitudeDegrees);
        double deltaLon = Math.toRadians(toLongitudeDegrees - fromLongitudeDegrees);

        double y = Math.sin(deltaLon) * Math.cos(toLat);
        double x = Math.cos(fromLat) * Math.sin(toLat)
                - Math.sin(fromLat) * Math.cos(toLat) * Math.cos(deltaLon);
        return normalize360(Math.toDegrees(Math.atan2(y, x)));
    }

    public static double normalize360(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }

    /** Normalizes an angular error to [-180, 180). Positive means turn clockwise/right. */
    public static double normalizeSigned180(double degrees) {
        double normalized = normalize360(degrees + 180.0) - 180.0;
        return normalized == 180.0 ? -180.0 : normalized;
    }
}
