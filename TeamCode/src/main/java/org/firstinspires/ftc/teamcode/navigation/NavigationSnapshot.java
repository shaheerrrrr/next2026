package org.firstinspires.ftc.teamcode.navigation;

/** One fused Control Hub view of ESP navigation data and local IMU heading. */
public final class NavigationSnapshot {
    public final EspNavigationData esp;
    public final double imuYawDegrees;
    public final double compassHeadingDegrees;
    public final double distanceToTargetMeters;
    public final double bearingToTargetDegrees;
    public final double headingErrorDegrees;
    public final long navigationSequenceAgeMs;
    public final boolean readyForPointToPoint;
    public final String readinessReason;

    public NavigationSnapshot(
            EspNavigationData esp,
            double imuYawDegrees,
            double compassHeadingDegrees,
            double distanceToTargetMeters,
            double bearingToTargetDegrees,
            double headingErrorDegrees,
            long navigationSequenceAgeMs,
            boolean readyForPointToPoint,
            String readinessReason) {
        this.esp = esp;
        this.imuYawDegrees = imuYawDegrees;
        this.compassHeadingDegrees = compassHeadingDegrees;
        this.distanceToTargetMeters = distanceToTargetMeters;
        this.bearingToTargetDegrees = bearingToTargetDegrees;
        this.headingErrorDegrees = headingErrorDegrees;
        this.navigationSequenceAgeMs = navigationSequenceAgeMs;
        this.readyForPointToPoint = readyForPointToPoint;
        this.readinessReason = readinessReason;
    }
}
