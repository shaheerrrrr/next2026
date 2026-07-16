package org.firstinspires.ftc.teamcode.subsystems;

import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.TouchSensor;
import com.qualcomm.robotcore.util.ElapsedTime;

public class Intake {
    private static final String INTAKE_MOTOR_NAME = "IntakeMotor";
    private static final String LEFT_LIFT_SERVO_NAME = "IntakeLiftLeft";
    private static final String RIGHT_LIFT_SERVO_NAME = "IntakeLiftRight";
    private static final String LEFT_BOTTOM_SENSOR_NAME = "IntakeBottomLeft";
    private static final String RIGHT_BOTTOM_SENSOR_NAME = "IntakeBottomRight";

    private static final double LIFT_POWER = 0.75;

    // Primary endpoint tuning value: upward run time from a confirmed bottom position.
    public static final double RETRACT_SECONDS = 1.85;

    // Safety limits. These should remain comfortably above normal travel/skew, not be used
    // to force the mechanism against its stops.
    private static final double MAX_DEPLOY_SECONDS = 3.0;
    private static final double MAX_BOTTOM_SIDE_LAG_SECONDS = 0.75;
    private static final double MAX_LOOP_DELTA_SECONDS = 0.10;
    private static final double POWER_DEADBAND = 0.01;

    private final DcMotor motor;
    private final CRServo leftLiftServo;
    private final CRServo rightLiftServo;
    private final TouchSensor leftBottomSensor;
    private final TouchSensor rightBottomSensor;
    private final ElapsedTime stateTimer = new ElapsedTime();
    private final ElapsedTime updateTimer = new ElapsedTime();

    // Estimated distance below the top, represented as equivalent lift run time.
    private double leftPositionSeconds;
    private double rightPositionSeconds;
    private boolean leftBottomLatched;
    private boolean rightBottomLatched;
    private double firstBottomTimeSeconds = Double.NaN;
    private String fault = "";

    private State state = State.RETRACTED;

    private enum State {
        RETRACTED,
        DEPLOYING,
        INTAKING,
        RETRACTING,
        DEPLOY_FAULT
    }

    public Intake(HardwareMap hardwareMap) {
        motor = hardwareMap.get(DcMotor.class, INTAKE_MOTOR_NAME);
        leftLiftServo = hardwareMap.get(CRServo.class, LEFT_LIFT_SERVO_NAME);
        rightLiftServo = hardwareMap.get(CRServo.class, RIGHT_LIFT_SERVO_NAME);
        leftBottomSensor = hardwareMap.get(TouchSensor.class, LEFT_BOTTOM_SENSOR_NAME);
        rightBottomSensor = hardwareMap.get(TouchSensor.class, RIGHT_BOTTOM_SENSOR_NAME);

        motor.setDirection(DcMotorSimple.Direction.FORWARD);
        motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);

        leftLiftServo.setDirection(DcMotorSimple.Direction.FORWARD);
        rightLiftServo.setDirection(DcMotorSimple.Direction.REVERSE);

        stateTimer.reset();
        updateTimer.reset();
    }

    public void setPower(double power) {
        double deltaSeconds = Math.min(updateTimer.seconds(), MAX_LOOP_DELTA_SECONDS);
        updateTimer.reset();

        boolean requested = Math.abs(power) > POWER_DEADBAND;

        if (requested) {
            if (state == State.RETRACTED || state == State.RETRACTING) {
                beginDeploying();
            }
        } else if (state == State.DEPLOYING
                || state == State.INTAKING
                || state == State.DEPLOY_FAULT) {
            beginRetracting();
        }

        update(power, requested, deltaSeconds);
    }

    public void stop() {
        motor.setPower(0);
        stopLift();
    }

    public String getStateName() {
        return state.name();
    }

    public boolean isLeftBottomPressed() {
        return leftBottomSensor.isPressed();
    }

    public boolean isRightBottomPressed() {
        return rightBottomSensor.isPressed();
    }

    public boolean hasFault() {
        return !fault.isEmpty();
    }

    public String getFault() {
        return fault;
    }

    public double getLeftPositionEstimate() {
        return RETRACT_SECONDS <= 0 ? 0 : leftPositionSeconds / RETRACT_SECONDS;
    }

    public double getRightPositionEstimate() {
        return RETRACT_SECONDS <= 0 ? 0 : rightPositionSeconds / RETRACT_SECONDS;
    }

    private void beginDeploying() {
        state = State.DEPLOYING;
        stateTimer.reset();
        fault = "";
        leftBottomLatched = false;
        rightBottomLatched = false;
        firstBottomTimeSeconds = Double.NaN;
        captureBottomSwitches();
    }

    private void beginRetracting() {
        // Capture a switch that may have closed immediately before button release.
        captureBottomSwitches();
        state = State.RETRACTING;
        stateTimer.reset();
    }

    private void update(double intakePower, boolean requested, double deltaSeconds) {
        switch (state) {
            case DEPLOYING:
                updateDeploying(intakePower, deltaSeconds);
                break;

            case INTAKING:
                stopLift();
                motor.setPower(requested ? intakePower : 0);
                break;

            case RETRACTING:
                motor.setPower(0);
                updateRetracting(deltaSeconds);
                break;

            case DEPLOY_FAULT:
                motor.setPower(0);
                stopLift();
                break;

            case RETRACTED:
            default:
                motor.setPower(0);
                stopLift();
                break;
        }
    }

    private void updateDeploying(double intakePower, double deltaSeconds) {
        captureBottomSwitches();

        if (!leftBottomLatched) {
            leftPositionSeconds = Math.min(
                    RETRACT_SECONDS,
                    leftPositionSeconds + deltaSeconds);
        }
        if (!rightBottomLatched) {
            rightPositionSeconds = Math.min(
                    RETRACT_SECONDS,
                    rightPositionSeconds + deltaSeconds);
        }

        setLiftPowers(
                leftBottomLatched ? 0 : LIFT_POWER,
                rightBottomLatched ? 0 : LIFT_POWER);
        motor.setPower(intakePower);

        if (leftBottomLatched && rightBottomLatched) {
            stopLift();
            state = State.INTAKING;
            return;
        }

        double elapsed = stateTimer.seconds();
        if (elapsed >= MAX_DEPLOY_SECONDS) {
            enterDeployFault("Bottom switch timeout");
        } else if (!Double.isNaN(firstBottomTimeSeconds)
                && elapsed - firstBottomTimeSeconds >= MAX_BOTTOM_SIDE_LAG_SECONDS) {
            enterDeployFault("Bottom switches reached too far apart");
        }
    }

    private void updateRetracting(double deltaSeconds) {
        boolean moveLeft = leftPositionSeconds > 0;
        boolean moveRight = rightPositionSeconds > 0;

        setLiftPowers(
                moveLeft ? -LIFT_POWER : 0,
                moveRight ? -LIFT_POWER : 0);

        if (moveLeft) leftPositionSeconds = Math.max(0, leftPositionSeconds - deltaSeconds);
        if (moveRight) rightPositionSeconds = Math.max(0, rightPositionSeconds - deltaSeconds);

        if (leftPositionSeconds <= 0 && rightPositionSeconds <= 0) {
            stopLift();
            state = State.RETRACTED;
        }
    }

    private void captureBottomSwitches() {
        double elapsed = stateTimer.seconds();

        if (!leftBottomLatched && leftBottomSensor.isPressed()) {
            leftBottomLatched = true;
            leftPositionSeconds = RETRACT_SECONDS;
            if (Double.isNaN(firstBottomTimeSeconds)) firstBottomTimeSeconds = elapsed;
        }

        if (!rightBottomLatched && rightBottomSensor.isPressed()) {
            rightBottomLatched = true;
            rightPositionSeconds = RETRACT_SECONDS;
            if (Double.isNaN(firstBottomTimeSeconds)) firstBottomTimeSeconds = elapsed;
        }
    }

    private void enterDeployFault(String message) {
        fault = message;
        state = State.DEPLOY_FAULT;
        motor.setPower(0);
        stopLift();
    }

    private void setLiftPowers(double leftPower, double rightPower) {
        leftLiftServo.setPower(leftPower);
        rightLiftServo.setPower(rightPower);
    }

    private void stopLift() {
        setLiftPowers(0, 0);
    }
}
