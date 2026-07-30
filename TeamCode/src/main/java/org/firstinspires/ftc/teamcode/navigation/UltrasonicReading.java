package org.firstinspires.ftc.teamcode.navigation;

/** Immutable result of one FSON ultrasonic frame read through the robot-side Pico bridge. */
public final class UltrasonicReading {
    public enum Status {
        OK,
        I2C_ERROR,
        WRONG_LENGTH,
        BAD_MAGIC,
        BAD_VERSION,
        BAD_PACKET_LENGTH,
        BAD_CHECKSUM
    }

    /** Wire sentinel meaning the firmware completed a ping cycle with no usable echo. */
    public static final int NO_READING_MILLIMETERS = 0xFFFF;

    public final Status status;
    public final String error;
    public final long controlHubReadTimeNanos;
    /**
     * Wire value is uint16 and wraps at 65536. Widened to a signed int so the full unsigned
     * range is representable, the same way {@link EspNavigationData} widens its uint32 wire
     * fields to long.
     */
    public final int sampleSeq;
    /**
     * Wire value is uint16 millimetres, widened to a signed int for the full unsigned range.
     * Equals {@link #NO_READING_MILLIMETERS} when the firmware has no usable echo; use
     * {@link #hasDistance()} rather than comparing this field directly.
     */
    public final int distanceMillimeters;

    UltrasonicReading(
            Status status,
            String error,
            long controlHubReadTimeNanos,
            int sampleSeq,
            int distanceMillimeters) {
        this.status = status;
        this.error = error;
        this.controlHubReadTimeNanos = controlHubReadTimeNanos;
        this.sampleSeq = sampleSeq;
        this.distanceMillimeters = distanceMillimeters;
    }

    static UltrasonicReading invalid(Status status, String error, long readTimeNanos) {
        return new UltrasonicReading(
                status,
                error,
                readTimeNanos,
                0,
                NO_READING_MILLIMETERS);
    }

    /** True when the I2C transaction itself produced a well-formed, checksum-clean frame. */
    public boolean packetValid() {
        return status == Status.OK;
    }

    /**
     * True when the frame is valid and carries an actual measurement. A valid frame reporting
     * the no-reading sentinel is normal for this sensor: a timeout, an out-of-range target, or
     * a weak echo off an angled surface all produce a live device with nothing to report. That
     * is a different condition from a failed read, so callers must distinguish the two.
     */
    public boolean hasDistance() {
        return packetValid() && distanceMillimeters != NO_READING_MILLIMETERS;
    }

    /**
     * Distance in metres, or {@link Double#NaN} when {@link #hasDistance()} is false. NaN is
     * used for unavailable derived values throughout this package, so an absent reading can
     * never be mistaken for a legitimate zero-distance measurement.
     */
    public double distanceMeters() {
        return hasDistance() ? distanceMillimeters / 1000.0 : Double.NaN;
    }
}
