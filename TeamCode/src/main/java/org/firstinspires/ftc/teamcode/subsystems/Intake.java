package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.util.ElapsedTime;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.HardwareMap;

public class Intake {
    private static final String INTAKE_MOTOR_NAME = "IntakeMotor";
    private static final String LEFT_LIFT_SERVO_NAME = "IntakeLiftLeft";
    private static final String RIGHT_LIFT_SERVO_NAME = "IntakeLiftRight";

    private static final double LIFT_POWER = 0.75;
    private static final double TRAVEL_SECONDS = 1.85;
    private static final double RETRACT_EXTRA_SECONDS = 0.4;

    private final DcMotor motor;
    private final CRServo leftLiftServo;
    private final CRServo rightLiftServo;
    private final ElapsedTime stateTimer = new ElapsedTime();

    private State state = State.RETRACTED;

    private enum State {
        RETRACTED,
        DEPLOYING,
        INTAKING,
        RETRACTING
    }

    public Intake(HardwareMap hardwareMap) {
        motor = hardwareMap.get(DcMotor.class, INTAKE_MOTOR_NAME);
        leftLiftServo = hardwareMap.get(CRServo.class, LEFT_LIFT_SERVO_NAME);
        rightLiftServo = hardwareMap.get(CRServo.class, RIGHT_LIFT_SERVO_NAME);

        motor.setDirection(DcMotorSimple.Direction.FORWARD);
        motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);

        leftLiftServo.setDirection(DcMotorSimple.Direction.FORWARD);
        rightLiftServo.setDirection(DcMotorSimple.Direction.REVERSE);
    }

    public void setPower(double power) {
        if (power != 0) {
            if (state == State.RETRACTED || state == State.RETRACTING) {
                state = State.DEPLOYING;
                stateTimer.reset();
            }

            if (state == State.DEPLOYING && stateTimer.seconds() >= TRAVEL_SECONDS) {
                state = State.INTAKING;
            }
        } else if (state == State.DEPLOYING || state == State.INTAKING) {
            state = State.RETRACTING;
            stateTimer.reset();
        }

        update(power);
    }

    public void stop() {
        motor.setPower(0);
        stopLift();
        state = State.RETRACTED;
    }

    private void update(double intakePower) {
        switch (state) {
            case DEPLOYING:
                setLiftPower(LIFT_POWER);
                motor.setPower(intakePower);
                break;
            case INTAKING:
                stopLift();
                motor.setPower(intakePower);
                break;
            case RETRACTING:
                motor.setPower(0);
                if (stateTimer.seconds() < TRAVEL_SECONDS + RETRACT_EXTRA_SECONDS) {
                    setLiftPower(-LIFT_POWER);
                } else {
                    stopLift();
                    state = State.RETRACTED;
                }
                break;
            case RETRACTED:
            default:
                motor.setPower(0);
                stopLift();
                break;
        }
    }

    private void setLiftPower(double power) {
        leftLiftServo.setPower(power);
        rightLiftServo.setPower(power);
    }

    private void stopLift() {
        setLiftPower(0);
    }
}
