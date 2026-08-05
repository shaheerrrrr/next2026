package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import org.firstinspires.ftc.teamcode.subsystems.CandyCane;
import org.firstinspires.ftc.teamcode.subsystems.Drivetrain;
import org.firstinspires.ftc.teamcode.subsystems.ChudIntake;

@TeleOp(name = "Flash", group = "TeleOp")
public class FlashTeleOp extends LinearOpMode {
    private static final double GOON_DIRECTION_SECONDS = 1.25;
    private static final double GOON_DRIVE_POWER = 0.75;
    private static final double GOON_INPUT_DEADBAND = 0.15;

    // Manual forward input is negative in this deployed drivetrain convention.
    private static final double GOON_FORWARD_SIGN = -1.0;

    private boolean gooning = false;
    private boolean goonForward = true;
    private long goonDirectionStartedMs = 0;

    @Override
    public void runOpMode() {
        Drivetrain drivetrain = new Drivetrain(hardwareMap);
        CandyCane candyCane = new CandyCane(hardwareMap);
        ChudIntake intake = new Intake(hardwareMap);

        waitForStart();

        boolean lastX = gamepad1.x;

        while (opModeIsActive()) {
            long nowMs = monotonicMs();
            boolean x = gamepad1.x;
            boolean xPressed = x && !lastX;
            lastX = x;

            if (xPressed) {
                beginGooning(nowMs);
            }

            if (gooning && hasGooningInterruptInput()) {
                gooning = false;
                drivetrain.stop();
            }

            if (gooning) {
                long directionDurationMs = Math.max(
                        1L,
                        Math.round(GOON_DIRECTION_SECONDS * 1000.0));
                if (nowMs - goonDirectionStartedMs >= directionDurationMs) {
                    goonForward = !goonForward;
                    goonDirectionStartedMs = nowMs;
                }

                double directionSign = goonForward ? GOON_FORWARD_SIGN : -GOON_FORWARD_SIGN;
                drivetrain.drive(directionSign * GOON_DRIVE_POWER, 0);
            } else {
                drivetrain.drive(gamepad1.left_stick_y, gamepad1.right_stick_x);
            }

            candyCane.setPower(gamepad1.right_trigger - gamepad1.left_trigger);
            intake.setPower((gamepad1.right_bumper ? 1 : 0) - (gamepad1.left_bumper ? 1 : 0));
        }

        drivetrain.stop();
        candyCane.stop();
        intake.stop();
    }

    private void beginGooning(long nowMs) {
        gooning = true;
        goonForward = true;
        goonDirectionStartedMs = nowMs;
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
}
