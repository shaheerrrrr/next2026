package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

public class Drivetrain {
    private static final String LEFT_MOTOR_NAME  = "LeftMotor";
    private static final String RIGHT_MOTOR_NAME = "RightMotor";

    // Motor-power units per second. These limit responsiveness, not maximum speed.
    private static final double MAX_DRIVE_ACCEL = 1.5;
    private static final double MAX_TURN_ACCEL = 2.5;

    // A delayed control loop must not be allowed to cause one large power jump.
    private static final double MAX_SLEW_TIMESTEP_SECONDS = 0.1;

    private final DcMotor leftMotor;
    private final DcMotor rightMotor;

    private double currentDrive = 0;
    private double currentTurn = 0;
    private long lastDriveTimeNanos = 0;

    public Drivetrain(HardwareMap hardwareMap) {
        leftMotor  = hardwareMap.get(DcMotor.class, LEFT_MOTOR_NAME);
        rightMotor = hardwareMap.get(DcMotor.class, RIGHT_MOTOR_NAME);

        leftMotor.setDirection(DcMotorSimple.Direction.REVERSE);
        rightMotor.setDirection(DcMotorSimple.Direction.FORWARD);

        leftMotor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        rightMotor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);

        leftMotor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        rightMotor.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
    }

    public void drive(double drive, double turn) {
        long now = System.nanoTime();
        double elapsedSeconds = 0;
        if (lastDriveTimeNanos != 0) {
            elapsedSeconds = Math.min(
                    (now - lastDriveTimeNanos) / 1_000_000_000.0,
                    MAX_SLEW_TIMESTEP_SECONDS
            );
        }
        lastDriveTimeNanos = now;

        currentDrive = slew(
                currentDrive,
                drive,
                MAX_DRIVE_ACCEL * elapsedSeconds
        );
        currentTurn = slew(
                currentTurn,
                turn,
                MAX_TURN_ACCEL * elapsedSeconds
        );

        // Ramp translation and rotation independently, then combine them. If the
        // combination exceeds motor range, scale both equally to preserve its shape.
        double targetLeft  = currentDrive - currentTurn;
        double targetRight = currentDrive + currentTurn;

        double maxPower = Math.max(Math.abs(targetLeft), Math.abs(targetRight));
        if (maxPower > 1.0) {
            targetLeft  /= maxPower;
            targetRight /= maxPower;
        }

        leftMotor.setPower(targetLeft);
        rightMotor.setPower(targetRight);
    }

    public void stop() {
        currentDrive = 0;
        currentTurn = 0;
        lastDriveTimeNanos = 0;
        leftMotor.setPower(0);
        rightMotor.setPower(0);
    }

    private static double slew(double current, double target, double maxChange) {
        double delta = target - current;
        if (Math.abs(delta) <= maxChange) return target;
        return current + Math.copySign(maxChange, delta);
    }
}
