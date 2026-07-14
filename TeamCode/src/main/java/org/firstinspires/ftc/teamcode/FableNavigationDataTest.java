package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import org.firstinspires.ftc.teamcode.navigation.EspNavigationData;
import org.firstinspires.ftc.teamcode.navigation.NavigationSnapshot;
import org.firstinspires.ftc.teamcode.subsystems.NavigationSubsystem;

@TeleOp(name = "Fable: Navigation Data Test", group = "Test")
public class FableNavigationDataTest extends LinearOpMode {
    private static final long POLL_INTERVAL_MS = 1000;

    @Override
    public void runOpMode() {
        NavigationSubsystem navigation = new NavigationSubsystem(hardwareMap);
        telemetry.setMsTransmissionInterval((int) POLL_INTERVAL_MS);

        boolean lastY = false;
        while (opModeInInit()) {
            lastY = updateAndShow(navigation, lastY);
            sleep(POLL_INTERVAL_MS);
        }

        while (opModeIsActive()) {
            lastY = updateAndShow(navigation, lastY);
            sleep(POLL_INTERVAL_MS);
        }
    }

    private boolean updateAndShow(NavigationSubsystem navigation, boolean lastY) {
        boolean y = gamepad1.y;
        if (y && !lastY) navigation.resetHeadingToNorth();

        NavigationSnapshot snapshot = navigation.poll();
        EspNavigationData data = snapshot.esp;

        telemetry.addLine("DATA ONLY - this OpMode never commands the drivetrain");
        telemetry.addData("Ready", "%s (%s)",
                snapshot.readyForPointToPoint,
                snapshot.readinessReason);
        telemetry.addData("I2C", "%s%s",
                data.status,
                data.error.isEmpty() ? "" : ": " + data.error);
        telemetry.addData("Sequences", "nav=%d target=%d navAge=%d ms",
                data.navigationSequence,
                data.targetSequence,
                snapshot.navigationSequenceAgeMs);
        telemetry.addData("GPS fix", "%s valid=%s age=%d ms",
                data.fixQuality,
                data.locationValid(),
                data.gpsAgeMs);
        telemetry.addData("GPS quality", "sat=%d hdop=%.2f chars=%d",
                data.satellites,
                data.hdop,
                data.gpsCharsProcessed);
        telemetry.addData("Driver link", "alive=%s age=%d ms",
                data.driverLinkAlive(),
                data.driverLinkAgeMs);
        telemetry.addData("Current", "%.7f, %.7f",
                data.currentLatitudeDegrees,
                data.currentLongitudeDegrees);
        telemetry.addData("Target", "valid=%s %.7f, %.7f",
                data.targetValid(),
                data.targetLatitudeDegrees,
                data.targetLongitudeDegrees);
        telemetry.addData("IMU", "yaw=%.2f compass=%.2f deg",
                snapshot.imuYawDegrees,
                snapshot.compassHeadingDegrees);
        telemetry.addData("Target geometry", "distance=%.2f m bearing=%.2f deg",
                snapshot.distanceToTargetMeters,
                snapshot.bearingToTargetDegrees);
        telemetry.addData("Heading error", "%.2f deg (+ means turn right)",
                snapshot.headingErrorDegrees);
        telemetry.addData("ESP uptime", "%d ms", data.espUptimeMs);
        telemetry.addLine("Point Fable toward field north, then press Y/Triangle to zero heading.");
        telemetry.update();

        return y;
    }
}
