package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import org.firstinspires.ftc.teamcode.subsystems.CandyCane;
import org.firstinspires.ftc.teamcode.subsystems.Drivetrain;
import org.firstinspires.ftc.teamcode.subsystems.Intake;
import org.firstinspires.ftc.teamcode.subsystems.NavigationSubsystem;
import org.firstinspires.ftc.teamcode.navigation.NavigationSnapshot;
import org.firstinspires.ftc.teamcode.navigation.PointToPointController;

@TeleOp(name = "Fable", group = "TeleOp")
public class FableTeleOp extends LinearOpMode {
    private static final long TELEOP_NAV_POLL_MS = 1000;
    private static final long AUTO_NAV_POLL_MS = 200;
    private static final double MANUAL_OVERRIDE_DEADBAND = 0.18;

    // Gooning alternates between physical forward and backward motion.
    private static final double GOON_DIRECTION_SECONDS = 1.25;
    private static final double GOON_DRIVE_POWER = 0.75;
    private static final double GOON_INPUT_DEADBAND = 0.15;

    // Manual forward/right input is negative in this deployed drivetrain convention.
    private static final double AUTO_DRIVE_SIGN = -1.0;
    private static final double AUTO_TURN_SIGN = 1.0;

    private DriveMode driveMode = DriveMode.TELEOP;
    private String modeDetail = "Driver control";
    private long activeTargetSequence = -1;
    private long goonDirectionStartedMs = 0;
    private boolean goonForward = true;

    private enum DriveMode {
        TELEOP,
        AUTO_NAVIGATING,
        GOONING
    }

    @Override
    public void runOpMode() {
        Drivetrain drivetrain = new Drivetrain(hardwareMap);
        CandyCane candyCane = new CandyCane(hardwareMap);
        Intake intake = new Intake(hardwareMap);
        NavigationSubsystem navigation = new NavigationSubsystem(hardwareMap);
        PointToPointController pointController = new PointToPointController();

        telemetry.setMsTransmissionInterval(100);
        telemetry.log().setCapacity(12);

        long nextNavigationPollMs = 0;
        boolean lastY = false;
        NavigationSnapshot snapshot = null;
        PointToPointController.Output autoOutput =
                PointToPointController.Output.stopped("Not active", 0);

        try {
            while (opModeInInit()) {
                long nowMs = monotonicMs();
                if (nowMs >= nextNavigationPollMs) {
                    navigation.pollNavigationData();
                    nextNavigationPollMs = nowMs + TELEOP_NAV_POLL_MS;
                }

                boolean y = gamepad1.y;
                if (y && !lastY) {
                    navigation.resetHeadingToNorth();
                    telemetry.log().add("IMU heading zeroed to field north");
                }
                lastY = y;

                snapshot = navigation.snapshot();
                showTelemetry(snapshot, autoOutput, intake, true);
                sleep(20);
            }

            if (isStopRequested()) return;

            boolean lastA = gamepad1.a;
            boolean lastB = gamepad1.b;
            boolean lastX = gamepad1.x;
            nextNavigationPollMs = 0;

            while (opModeIsActive()) {
                long nowMs = monotonicMs();
                long pollInterval = driveMode == DriveMode.AUTO_NAVIGATING
                        ? AUTO_NAV_POLL_MS
                        : TELEOP_NAV_POLL_MS;
                if (nowMs >= nextNavigationPollMs) {
                    navigation.pollNavigationData();
                    nextNavigationPollMs = nowMs + pollInterval;
                }
                snapshot = navigation.snapshot();

                boolean a = gamepad1.a;
                boolean b = gamepad1.b;
                boolean x = gamepad1.x;
                boolean aPressed = a && !lastA;
                boolean bPressed = b && !lastB;
                boolean xPressed = x && !lastX;
                lastA = a;
                lastB = b;
                lastX = x;

                double manualDrive = gamepad1.left_stick_y;
                double manualTurn = gamepad1.right_stick_x;
                boolean manualOverride = Math.abs(manualDrive) > MANUAL_OVERRIDE_DEADBAND
                        || Math.abs(manualTurn) > MANUAL_OVERRIDE_DEADBAND;

                if (xPressed) {
                    pointController.reset();
                    beginGooning(nowMs, "Square/X started gooning");
                }

                if (driveMode == DriveMode.TELEOP) {
                    if (aPressed) {
                        // Force a fresh packet before deciding whether movement may begin.
                        navigation.pollNavigationData();
                        snapshot = navigation.snapshot();
                        nextNavigationPollMs = nowMs + AUTO_NAV_POLL_MS;

                        if (snapshot.readyForPointToPoint) {
                            activeTargetSequence = snapshot.esp.targetSequence;
                            pointController.reset();
                            setDriveMode(DriveMode.AUTO_NAVIGATING, "Autonomous navigation enabled");
                        } else {
                            modeDetail = "Auto refused: " + snapshot.readinessReason;
                            telemetry.log().add(modeDetail);
                        }
                    }

                    if (driveMode == DriveMode.TELEOP) {
                        drivetrain.drive(manualDrive, manualTurn);
                        autoOutput = PointToPointController.Output.stopped("Not active", 0);
                    }
                }

                if (driveMode == DriveMode.AUTO_NAVIGATING) {
                    if (bPressed) {
                        drivetrain.stop();
                        pointController.reset();
                        setDriveMode(DriveMode.TELEOP, "Driver cancelled with Circle/B");
                    } else if (manualOverride) {
                        drivetrain.stop();
                        pointController.reset();
                        setDriveMode(DriveMode.TELEOP, "Manual stick override");
                        drivetrain.drive(manualDrive, manualTurn);
                    } else if (!snapshot.readyForPointToPoint) {
                        drivetrain.stop();
                        pointController.reset();
                        setDriveMode(
                                DriveMode.TELEOP,
                                "Navigation stopped: " + snapshot.readinessReason);
                    } else if (snapshot.esp.targetSequence != activeTargetSequence) {
                        drivetrain.stop();
                        pointController.reset();
                        setDriveMode(DriveMode.TELEOP, "Target changed; press Cross/A to re-arm");
                    } else {
                        autoOutput = pointController.update(snapshot);
                        if (autoOutput.arrived) {
                            pointController.reset();
                            beginGooning(nowMs, "Target reached; gooning started");
                        } else {
                            drivetrain.drive(
                                    AUTO_DRIVE_SIGN * autoOutput.drive,
                                    AUTO_TURN_SIGN * autoOutput.turn);
                            modeDetail = autoOutput.phase;
                        }
                    }
                }

                if (driveMode == DriveMode.GOONING) {
                    autoOutput = PointToPointController.Output.stopped("Gooning", 0);
                    if (hasGooningInterruptInput()) {
                        drivetrain.stop();
                        pointController.reset();
                        setDriveMode(DriveMode.TELEOP, "Gooning interrupted by driver input");
                        if (manualOverride) {
                            drivetrain.drive(manualDrive, manualTurn);
                        }
                    } else {
                        long directionDurationMs = Math.max(
                                1L,
                                Math.round(GOON_DIRECTION_SECONDS * 1000.0));
                        if (nowMs - goonDirectionStartedMs >= directionDurationMs) {
                            goonForward = !goonForward;
                            goonDirectionStartedMs = nowMs;
                        }

                        double directionSign = goonForward ? AUTO_DRIVE_SIGN : -AUTO_DRIVE_SIGN;
                        drivetrain.drive(directionSign * GOON_DRIVE_POWER, 0);
                        modeDetail = String.format(
                                "Gooning %s (%.1fs each way)",
                                goonForward ? "forward" : "backward",
                                GOON_DIRECTION_SECONDS);
                    }
                }

                // Mechanism controls remain available in every drivetrain mode.
                candyCane.setPower(gamepad1.right_trigger - gamepad1.left_trigger);
                intake.setPower(
                        (gamepad1.right_bumper ? 1 : 0)
                                - (gamepad1.left_bumper ? 1 : 0));

                showTelemetry(snapshot, autoOutput, intake, false);
            }
        } finally {
            drivetrain.stop();
            candyCane.stop();
            intake.stop();
        }
    }

    private void setDriveMode(DriveMode newMode, String detail) {
        if (driveMode != newMode || !modeDetail.equals(detail)) {
            telemetry.log().add("Drive mode: %s -> %s (%s)", driveMode, newMode, detail);
        }
        driveMode = newMode;
        modeDetail = detail;
    }

    private void beginGooning(long nowMs, String detail) {
        goonForward = true;
        goonDirectionStartedMs = nowMs;
        setDriveMode(DriveMode.GOONING, detail);
    }

    private boolean hasGooningInterruptInput() {
        return gamepad1.a
                || gamepad1.b
                || gamepad1.y
                || gamepad1.dpad_up
                || gamepad1.dpad_down
                || gamepad1.dpad_left
                || gamepad1.dpad_right
                || gamepad1.start
                || gamepad1.back
                || gamepad1.left_bumper
                || gamepad1.right_bumper
                || gamepad1.left_trigger > GOON_INPUT_DEADBAND
                || gamepad1.right_trigger > GOON_INPUT_DEADBAND
                || Math.abs(gamepad1.left_stick_x) > GOON_INPUT_DEADBAND
                || Math.abs(gamepad1.left_stick_y) > GOON_INPUT_DEADBAND
                || Math.abs(gamepad1.right_stick_x) > GOON_INPUT_DEADBAND
                || Math.abs(gamepad1.right_stick_y) > GOON_INPUT_DEADBAND;
    }

    private static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private void showTelemetry(
            NavigationSnapshot snapshot,
            PointToPointController.Output autoOutput,
            Intake intake,
            boolean initializing) {
        telemetry.addData("Drive mode", driveMode);
        telemetry.addData("Mode detail", modeDetail);
        telemetry.addData(
                "Navigation ready",
                "%s (%s)",
                snapshot.readyForPointToPoint,
                snapshot.readinessReason);
        telemetry.addData(
                "GPS",
                "fix=%s valid=%s age=%dms sat=%d hdop=%.2f",
                snapshot.esp.fixQuality,
                snapshot.esp.locationValid(),
                snapshot.esp.gpsAgeMs,
                snapshot.esp.satellites,
                snapshot.esp.hdop);
        telemetry.addData(
                "Current",
                "%.7f, %.7f",
                snapshot.esp.currentLatitudeDegrees,
                snapshot.esp.currentLongitudeDegrees);
        telemetry.addData(
                "Target",
                "valid=%s seq=%d %.7f, %.7f",
                snapshot.esp.targetValid(),
                snapshot.esp.targetSequence,
                snapshot.esp.targetLatitudeDegrees,
                snapshot.esp.targetLongitudeDegrees);
        telemetry.addData(
                "Geometry",
                "distance=%.1fm bearing=%.1f heading=%.1f error=%.1f",
                snapshot.distanceToTargetMeters,
                snapshot.bearingToTargetDegrees,
                snapshot.compassHeadingDegrees,
                snapshot.headingErrorDegrees);
        telemetry.addData(
                "Auto command",
                "drive=%.2f turn=%.2f arrival=%d/%d",
                autoOutput.drive,
                autoOutput.turn,
                autoOutput.arrivalFixCount,
                PointToPointController.ARRIVAL_CONFIRMATION_FIXES);
        telemetry.addData(
                "Data health",
                "packet=%s navSeq=%d seqAge=%dms",
                snapshot.esp.status,
                snapshot.esp.navigationSequence,
                snapshot.navigationSequenceAgeMs);
        telemetry.addData(
                "ESP-NOW (informational)",
                "alive=%s commandAge=%dms",
                snapshot.esp.driverLinkAlive(),
                snapshot.esp.driverLinkAgeMs);
        telemetry.addData(
                "Intake lift",
                "%s bottom L=%s R=%s position L=%.0f%% R=%.0f%%",
                intake.getStateName(),
                intake.isLeftBottomPressed(),
                intake.isRightBottomPressed(),
                intake.getLeftPositionEstimate() * 100,
                intake.getRightPositionEstimate() * 100);
        telemetry.addData(
                "Intake fault",
                intake.hasFault() ? intake.getFault() : "none");
        if (initializing) {
            telemetry.addLine("Point Fable toward field north and press Triangle/Y, then START.");
        } else {
            telemetry.addLine(
                    "Cross/A: start auto | Square/X: gooning | any other input: interrupt");
        }
        telemetry.update();
    }
}
