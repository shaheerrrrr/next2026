package org.firstinspires.ftc.teamcode.navigation;

/** Converts point-to-point navigation geometry into fast forward-arc drive commands. */
public final class PointToPointController {
    public static final double ARRIVAL_RADIUS_METERS = 3.0;
    public static final int ARRIVAL_CONFIRMATION_FIXES = 3;

    public static final double MAX_DRIVE_POWER = 0.90;
    public static final double MIN_DRIVE_POWER = 0.50;
    public static final double SLOWDOWN_RADIUS_METERS = 35.0;

    public static final double HEADING_KP = 0.020;
    public static final double MAX_TURN_POWER = 0.70;
    public static final double MIN_TURN_POWER = 0.15;
    public static final double HEADING_DEADBAND_DEGREES = 2.0;
    public static final double MAX_TURN_TO_DRIVE_RATIO = 0.78;

    private long lastArrivalSequence = -1;
    private int arrivalFixCount;

    public void reset() {
        lastArrivalSequence = -1;
        arrivalFixCount = 0;
    }

    public Output update(NavigationSnapshot snapshot) {
        double distance = snapshot.distanceToTargetMeters;
        double headingError = snapshot.headingErrorDegrees;

        if (!Double.isFinite(distance) || !Double.isFinite(headingError)) {
            return Output.stopped("Navigation geometry is unavailable", arrivalFixCount);
        }

        updateArrivalConfirmation(snapshot.esp.navigationSequence, distance);
        if (arrivalFixCount >= ARRIVAL_CONFIRMATION_FIXES) {
            return new Output(0, 0, true, arrivalFixCount, "Arrival confirmed");
        }

        // Stop while waiting for enough independent GPS fixes inside the arrival circle.
        if (distance <= ARRIVAL_RADIUS_METERS) {
            return Output.stopped("Inside arrival radius; confirming GPS fixes", arrivalFixCount);
        }

        double drive = calculateDrive(distance);
        double turn = calculateTurn(headingError, drive);

        return new Output(drive, turn, false, arrivalFixCount, "Fast forward arc toward target");
    }

    private void updateArrivalConfirmation(long navigationSequence, double distance) {
        if (navigationSequence == lastArrivalSequence) return;

        lastArrivalSequence = navigationSequence;
        if (distance <= ARRIVAL_RADIUS_METERS) {
            arrivalFixCount++;
        } else {
            arrivalFixCount = 0;
        }
    }

    private static double calculateTurn(double headingErrorDegrees, double drivePower) {
        double absoluteError = Math.abs(headingErrorDegrees);
        if (absoluteError <= HEADING_DEADBAND_DEGREES) return 0;

        // Keeping turn below forward power prevents either side from reversing. Fable steers
        // along an arc even when the target begins behind it instead of attempting a point turn.
        double maximumArcTurn = Math.min(
                MAX_TURN_POWER,
                drivePower * MAX_TURN_TO_DRIVE_RATIO);
        double magnitude = clamp(
                absoluteError * HEADING_KP,
                MIN_TURN_POWER,
                maximumArcTurn);
        return Math.copySign(magnitude, headingErrorDegrees);
    }

    private static double calculateDrive(double distanceMeters) {
        double progress = clamp(
                (distanceMeters - ARRIVAL_RADIUS_METERS)
                        / (SLOWDOWN_RADIUS_METERS - ARRIVAL_RADIUS_METERS),
                0,
                1);
        return MIN_DRIVE_POWER + progress * (MAX_DRIVE_POWER - MIN_DRIVE_POWER);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class Output {
        public final double drive;
        public final double turn;
        public final boolean arrived;
        public final int arrivalFixCount;
        public final String phase;

        Output(
                double drive,
                double turn,
                boolean arrived,
                int arrivalFixCount,
                String phase) {
            this.drive = drive;
            this.turn = turn;
            this.arrived = arrived;
            this.arrivalFixCount = arrivalFixCount;
            this.phase = phase;
        }

        public static Output stopped(String phase, int arrivalFixCount) {
            return new Output(0, 0, false, arrivalFixCount, phase);
        }
    }
}
