package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

public class Drivetrain {
    private static final String LEFT_MOTOR_NAME  = "LeftMotor";
    private static final String RIGHT_MOTOR_NAME = "RightMotor";

    // Max power change allowed per loop iteration (~50 Hz → full speed in ~0.4 s)
    private static final double MAX_ACCEL = 0.05;

    private final DcMotor leftMotor;
    private final DcMotor rightMotor;

    private double currentLeft  = 0;
    private double currentRight = 0;

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
        double targetLeft  = drive - turn;
        double targetRight = drive + turn;

        double maxPower = Math.max(Math.abs(targetLeft), Math.abs(targetRight));
        if (maxPower > 1.0) {
            targetLeft  /= maxPower;
            targetRight /= maxPower;
        }

        currentLeft  = slew(currentLeft,  targetLeft);
        currentRight = slew(currentRight, targetRight);

        leftMotor.setPower(currentLeft);
        rightMotor.setPower(currentRight);
    }

    public void stop() {
        currentLeft  = 0;
        currentRight = 0;
        leftMotor.setPower(0);
        rightMotor.setPower(0);
    }

    private static double slew(double current, double target) {
        double delta = target - current;
        if (Math.abs(delta) <= MAX_ACCEL) return target;
        return current + Math.copySign(MAX_ACCEL, delta);
    }
}
