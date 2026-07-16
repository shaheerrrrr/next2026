package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.IMU;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.teamcode.navigation.EspNavigationData;
import org.firstinspires.ftc.teamcode.navigation.FableNavigationI2cDevice;
import org.firstinspires.ftc.teamcode.navigation.GeoNavigation;
import org.firstinspires.ftc.teamcode.navigation.NavigationSnapshot;

/**
 * Fuses explicitly-polled GPS/target data with the Control Hub's local IMU heading.
 * This class calculates navigation geometry but never commands the drivetrain.
 */
public class NavigationSubsystem {
    public static final String ESP_DEVICE_NAME = "FableNav";
    public static final String IMU_DEVICE_NAME = "imu";

    // Change these two values if the physical Control Hub mounting differs from this default.
    public static final RevHubOrientationOnRobot.LogoFacingDirection HUB_LOGO_FACING =
            RevHubOrientationOnRobot.LogoFacingDirection.UP;
    public static final RevHubOrientationOnRobot.UsbFacingDirection HUB_USB_FACING =
            RevHubOrientationOnRobot.UsbFacingDirection.BACKWARD;

    public static final long MAX_GPS_AGE_MS = 1_500;
    public static final long MAX_SEQUENCE_AGE_MS = 1_500;

    private final FableNavigationI2cDevice esp;
    private final IMU imu;

    private long lastNavigationSequence = -1;
    private long lastSequenceChangeNanos;
    private EspNavigationData latestEspData;

    public NavigationSubsystem(HardwareMap hardwareMap) {
        esp = hardwareMap.get(FableNavigationI2cDevice.class, ESP_DEVICE_NAME);
        imu = hardwareMap.get(IMU.class, IMU_DEVICE_NAME);

        RevHubOrientationOnRobot hubOrientation = new RevHubOrientationOnRobot(
                HUB_LOGO_FACING,
                HUB_USB_FACING);
        imu.initialize(new IMU.Parameters(hubOrientation));
    }

    /**
     * Reads and caches one complete navigation packet from the Pico. TeleOp calls this at 1 Hz
     * while idle and 5 Hz during point-to-point navigation.
     */
    public EspNavigationData pollNavigationData() {
        long nowNanos = System.nanoTime();
        latestEspData = esp.readNavigationData();

        if (latestEspData.packetValid()
                && latestEspData.navigationSequence != lastNavigationSequence) {
            lastNavigationSequence = latestEspData.navigationSequence;
            lastSequenceChangeNanos = nowNanos;
        }

        return latestEspData;
    }

    /** Reads the IMU and fuses it with the most recently cached Pico packet. */
    public NavigationSnapshot snapshot() {
        long nowNanos = System.nanoTime();
        EspNavigationData data = latestEspData;
        if (data == null) data = pollNavigationData();

        double imuYawDegrees = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
        // FTC yaw is counterclockwise-positive. GPS compass bearing is clockwise-positive.
        double compassHeadingDegrees = GeoNavigation.normalize360(-imuYawDegrees);

        long sequenceAgeMs = lastSequenceChangeNanos == 0
                ? Long.MAX_VALUE
                : (nowNanos - lastSequenceChangeNanos) / 1_000_000L;

        double distanceMeters = Double.NaN;
        double bearingDegrees = Double.NaN;
        double headingErrorDegrees = Double.NaN;

        if (data.locationValid() && data.targetValid()) {
            distanceMeters = GeoNavigation.distanceMeters(
                    data.currentLatitudeDegrees,
                    data.currentLongitudeDegrees,
                    data.targetLatitudeDegrees,
                    data.targetLongitudeDegrees);
            bearingDegrees = GeoNavigation.initialBearingDegrees(
                    data.currentLatitudeDegrees,
                    data.currentLongitudeDegrees,
                    data.targetLatitudeDegrees,
                    data.targetLongitudeDegrees);
            headingErrorDegrees = GeoNavigation.normalizeSigned180(
                    bearingDegrees - compassHeadingDegrees);
        }

        String readinessReason = readinessReason(data, sequenceAgeMs, nowNanos);
        return new NavigationSnapshot(
                data,
                imuYawDegrees,
                compassHeadingDegrees,
                distanceMeters,
                bearingDegrees,
                headingErrorDegrees,
                sequenceAgeMs,
                readinessReason.isEmpty(),
                readinessReason.isEmpty() ? "ready" : readinessReason);
    }

    /** Call while Fable is physically pointing toward the field's defined north direction. */
    public void resetHeadingToNorth() {
        imu.resetYaw();
    }

    private static String readinessReason(
            EspNavigationData data,
            long sequenceAgeMs,
            long nowNanos) {
        if (!data.packetValid()) return "I2C packet: " + data.status + " " + data.error;
        if (!data.locationValid()) return "GPS location is invalid";
        if (data.fixQuality != EspNavigationData.FixQuality.GOOD) {
            return "GPS fix is not GOOD: " + data.fixQuality;
        }
        long timeSinceReadMs = Math.max(
                0,
                (nowNanos - data.controlHubReadTimeNanos) / 1_000_000L);
        if (data.gpsAgeMs > MAX_GPS_AGE_MS
                || timeSinceReadMs > MAX_GPS_AGE_MS - data.gpsAgeMs) {
            return "GPS data is stale";
        }
        if (!data.targetValid()) return "Target is not valid";
        if (sequenceAgeMs > MAX_SEQUENCE_AGE_MS) return "ESP navigation sequence is not advancing";
        return "";
    }
}
