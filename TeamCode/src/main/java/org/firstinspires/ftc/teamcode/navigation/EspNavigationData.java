package org.firstinspires.ftc.teamcode.navigation;

/** Immutable result of one navigation packet read through the robot-side Pico bridge. */
public final class EspNavigationData {
    public enum Status {
        OK,
        I2C_ERROR,
        WRONG_LENGTH,
        BAD_MAGIC,
        BAD_VERSION,
        BAD_PACKET_LENGTH,
        BAD_CHECKSUM
    }

    public enum FixQuality {
        NONE,
        WEAK,
        GOOD,
        UNKNOWN;

        static FixQuality fromWireValue(int value) {
            switch (value) {
                case 0:
                    return NONE;
                case 1:
                    return WEAK;
                case 2:
                    return GOOD;
                default:
                    return UNKNOWN;
            }
        }
    }

    public final Status status;
    public final String error;
    public final long controlHubReadTimeNanos;
    public final int flags;
    public final FixQuality fixQuality;
    public final long navigationSequence;
    public final long targetSequence;
    public final double currentLatitudeDegrees;
    public final double currentLongitudeDegrees;
    public final double targetLatitudeDegrees;
    public final double targetLongitudeDegrees;
    public final long gpsAgeMs;
    public final long driverLinkAgeMs;
    public final double hdop;
    public final int satellites;
    public final long gpsCharsProcessed;
    public final long espUptimeMs;

    EspNavigationData(
            Status status,
            String error,
            long controlHubReadTimeNanos,
            int flags,
            FixQuality fixQuality,
            long navigationSequence,
            long targetSequence,
            double currentLatitudeDegrees,
            double currentLongitudeDegrees,
            double targetLatitudeDegrees,
            double targetLongitudeDegrees,
            long gpsAgeMs,
            long driverLinkAgeMs,
            double hdop,
            int satellites,
            long gpsCharsProcessed,
            long espUptimeMs) {
        this.status = status;
        this.error = error;
        this.controlHubReadTimeNanos = controlHubReadTimeNanos;
        this.flags = flags;
        this.fixQuality = fixQuality;
        this.navigationSequence = navigationSequence;
        this.targetSequence = targetSequence;
        this.currentLatitudeDegrees = currentLatitudeDegrees;
        this.currentLongitudeDegrees = currentLongitudeDegrees;
        this.targetLatitudeDegrees = targetLatitudeDegrees;
        this.targetLongitudeDegrees = targetLongitudeDegrees;
        this.gpsAgeMs = gpsAgeMs;
        this.driverLinkAgeMs = driverLinkAgeMs;
        this.hdop = hdop;
        this.satellites = satellites;
        this.gpsCharsProcessed = gpsCharsProcessed;
        this.espUptimeMs = espUptimeMs;
    }

    static EspNavigationData invalid(Status status, String error, long readTimeNanos) {
        return new EspNavigationData(
                status,
                error,
                readTimeNanos,
                0,
                FixQuality.NONE,
                0,
                0,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Double.NaN,
                0,
                0,
                0);
    }

    public boolean packetValid() {
        return status == Status.OK;
    }

    public boolean locationValid() {
        return packetValid()
                && (flags & FableNavigationI2cDevice.FLAG_LOCATION_VALID) != 0;
    }

    public boolean targetValid() {
        return packetValid()
                && (flags & FableNavigationI2cDevice.FLAG_TARGET_VALID) != 0;
    }

    public boolean satellitesValid() {
        return packetValid()
                && (flags & FableNavigationI2cDevice.FLAG_SATELLITES_VALID) != 0;
    }

    public boolean hdopValid() {
        return packetValid()
                && (flags & FableNavigationI2cDevice.FLAG_HDOP_VALID) != 0;
    }

    public boolean driverLinkAlive() {
        return packetValid()
                && (flags & FableNavigationI2cDevice.FLAG_DRIVER_LINK_ALIVE) != 0;
    }
}
