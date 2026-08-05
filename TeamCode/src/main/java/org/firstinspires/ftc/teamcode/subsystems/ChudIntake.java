package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

public class ChudIntake {
    private static final String INTAKE_MOTOR_NAME = "IntakeMotor";

    private final DcMotor motor;

    public ChudIntake(HardwareMap hardwareMap) {
        motor = hardwareMap.get(DcMotor.class, INTAKE_MOTOR_NAME);
        motor.setDirection(DcMotorSimple.Direction.FORWARD);
        motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
    }

    public void setPower(double power) {
        motor.setPower(power);
    }

    public String getStateName() {
        return "INTAKING";
    }

    public boolean isLeftBottomPressed() {
        return true;
    }

    public boolean isRightBottomPressed() {
        return true;
    }

    public boolean hasFault() {
        return false;
    }

    public String getFault() {
        return "";
    }

    public double getLeftPositionEstimate() {
        return 1.0;
    }

    public double getRightPositionEstimate() {
        return 1.0;
    }

    public void stop() {
        motor.setPower(0);
    }
}