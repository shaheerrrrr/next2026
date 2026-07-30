package org.firstinspires.ftc.teamcode.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ObstacleAvoidanceControllerTest {

    private static UltrasonicReading validReadingAt(double meters) {
        int millimeters = (int) Math.round(meters * 1000.0);
        return new UltrasonicReading(UltrasonicReading.Status.OK, "", 0, 1, millimeters);
    }

    private static UltrasonicReading noEchoReading() {
        return new UltrasonicReading(
                UltrasonicReading.Status.OK,
                "",
                0,
                1,
                UltrasonicReading.NO_READING_MILLIMETERS);
    }

    private static UltrasonicReading invalidReading() {
        return UltrasonicReading.invalid(UltrasonicReading.Status.I2C_ERROR, "bus timeout", 0);
    }

    @Test
    public void triggerEntry_enterVeeringRightWithExpectedCommand() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();

        ObstacleAvoidanceController.Command command = controller.update(
                validReadingAt(ObstacleAvoidanceController.TRIGGER_DISTANCE_METERS - 0.05),
                0);

        assertEquals(ObstacleAvoidanceController.State.VEERING_RIGHT, command.state);
        assertTrue(command.active);
        assertEquals(ObstacleAvoidanceController.VEER_DRIVE_POWER, command.drive, 1e-9);
        assertEquals(ObstacleAvoidanceController.VEER_TURN_POWER, command.turn, 1e-9);
    }

    @Test
    public void hysteresis_staysVeeringBetweenTriggerAndClear() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();
        controller.update(validReadingAt(ObstacleAvoidanceController.TRIGGER_DISTANCE_METERS - 0.05), 0);

        // Between TRIGGER (1.25) and CLEAR (1.50): must not hand back yet.
        ObstacleAvoidanceController.Command command = controller.update(validReadingAt(1.40), 50);

        assertEquals(ObstacleAvoidanceController.State.VEERING_RIGHT, command.state);
        assertTrue(command.active);
    }

    @Test
    public void fullClear_handsBackToInactive() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();
        controller.update(validReadingAt(ObstacleAvoidanceController.TRIGGER_DISTANCE_METERS - 0.05), 0);

        ObstacleAvoidanceController.Command command = controller.update(
                validReadingAt(ObstacleAvoidanceController.CLEAR_DISTANCE_METERS),
                50);

        assertEquals(ObstacleAvoidanceController.State.INACTIVE, command.state);
        assertFalse(command.active);
        assertEquals(0, command.drive, 1e-9);
        assertEquals(0, command.turn, 1e-9);
    }

    @Test
    public void closeRangeEntry_goesStraightToReversingLeft() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();

        ObstacleAvoidanceController.Command command = controller.update(
                validReadingAt(ObstacleAvoidanceController.CLOSE_RANGE_METERS - 0.10),
                0);

        assertEquals(ObstacleAvoidanceController.State.REVERSING_LEFT, command.state);
        assertTrue(command.active);
        assertEquals(-ObstacleAvoidanceController.REVERSE_DRIVE_POWER, command.drive, 1e-9);
        assertEquals(
                ObstacleAvoidanceController.REVERSE_TURN_SIGN
                        * ObstacleAvoidanceController.REVERSE_TURN_POWER,
                command.turn,
                1e-9);
    }

    @Test
    public void closeRangeChatterGuard_staysReversingLeft() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();
        controller.update(validReadingAt(ObstacleAvoidanceController.CLOSE_RANGE_METERS - 0.10), 0);

        // Between CLOSE_RANGE (0.45) and CLOSE_RANGE_CLEAR (0.60): must not exit yet.
        ObstacleAvoidanceController.Command command = controller.update(validReadingAt(0.50), 50);

        assertEquals(ObstacleAvoidanceController.State.REVERSING_LEFT, command.state);
        assertTrue(command.active);
    }

    @Test
    public void closeRangeExit_transitionsToVeeringRight() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();
        controller.update(validReadingAt(ObstacleAvoidanceController.CLOSE_RANGE_METERS - 0.10), 0);

        ObstacleAvoidanceController.Command command = controller.update(
                validReadingAt(ObstacleAvoidanceController.CLOSE_RANGE_CLEAR_METERS),
                50);

        assertEquals(ObstacleAvoidanceController.State.VEERING_RIGHT, command.state);
        assertTrue(command.active);
    }

    @Test
    public void failOpen_onI2cErrorWhileVeering() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();
        controller.update(validReadingAt(ObstacleAvoidanceController.TRIGGER_DISTANCE_METERS - 0.05), 0);

        ObstacleAvoidanceController.Command command = controller.update(invalidReading(), 50);

        assertEquals(ObstacleAvoidanceController.State.INACTIVE, command.state);
        assertFalse(command.active);
    }

    @Test
    public void failOpen_onI2cErrorWhileReversing() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();
        controller.update(validReadingAt(ObstacleAvoidanceController.CLOSE_RANGE_METERS - 0.10), 0);

        ObstacleAvoidanceController.Command command = controller.update(invalidReading(), 50);

        assertEquals(ObstacleAvoidanceController.State.INACTIVE, command.state);
        assertFalse(command.active);
    }

    @Test
    public void failOpen_onNoEchoSentinelWhileVeering() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();
        controller.update(validReadingAt(ObstacleAvoidanceController.TRIGGER_DISTANCE_METERS - 0.05), 0);

        UltrasonicReading noEcho = noEchoReading();
        assertTrue("sentinel frame should still be a valid packet", noEcho.packetValid());
        assertFalse("sentinel frame should not carry a distance", noEcho.hasDistance());

        ObstacleAvoidanceController.Command command = controller.update(noEcho, 50);

        assertEquals(ObstacleAvoidanceController.State.INACTIVE, command.state);
        assertFalse(command.active);
    }

    @Test
    public void faultCounting_incrementsOnlyOnPacketInvalidReads() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();

        ObstacleAvoidanceController.Command first = controller.update(invalidReading(), 0);
        assertEquals(1, first.consecutiveFaults);

        ObstacleAvoidanceController.Command second = controller.update(invalidReading(), 10);
        assertEquals(2, second.consecutiveFaults);

        // Valid-but-no-echo is a normal condition, not a fault: must not add to the streak.
        ObstacleAvoidanceController.Command third = controller.update(noEchoReading(), 20);
        assertTrue(third.consecutiveFaults < second.consecutiveFaults + 1);
        assertEquals(0, third.consecutiveFaults);

        ObstacleAvoidanceController.Command fourth = controller.update(invalidReading(), 30);
        assertEquals(1, fourth.consecutiveFaults);
    }

    @Test
    public void timeout_reportsTimedOutAndReturnsToInactive() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();
        UltrasonicReading obstacle = validReadingAt(1.0); // steady, below trigger, above close range

        long nowMs = 0;
        ObstacleAvoidanceController.Command command = controller.update(obstacle, nowMs);
        assertTrue(command.active);
        assertFalse(command.timedOut);

        boolean sawTimeout = false;
        while (nowMs <= ObstacleAvoidanceController.MAX_AVOIDANCE_MS + 500) {
            nowMs += 100;
            command = controller.update(obstacle, nowMs);
            if (command.timedOut) {
                sawTimeout = true;
                break;
            }
        }

        assertTrue("expected timeout to fire within MAX_AVOIDANCE_MS + margin", sawTimeout);
        assertEquals(ObstacleAvoidanceController.State.INACTIVE, command.state);
        assertFalse(command.active);
        assertEquals(0, command.drive, 1e-9);
        assertEquals(0, command.turn, 1e-9);

        // Controller must not stay stuck: the very next call re-evaluates from INACTIVE.
        ObstacleAvoidanceController.Command after = controller.update(obstacle, nowMs + 10);
        assertFalse(after.timedOut);
    }

    @Test
    public void reset_clearsStateAndTimeoutClock() {
        ObstacleAvoidanceController controller = new ObstacleAvoidanceController();
        UltrasonicReading obstacle = validReadingAt(1.0);

        controller.update(obstacle, 0);
        controller.update(obstacle, 5_000); // still under MAX_AVOIDANCE_MS, not timed out yet

        controller.reset();

        // If the timeout clock were not cleared, this call at nowMs=12_000 would immediately
        // report timedOut (12_000 - 0 > MAX_AVOIDANCE_MS). It must not.
        ObstacleAvoidanceController.Command command = controller.update(obstacle, 12_000);

        assertEquals(ObstacleAvoidanceController.State.VEERING_RIGHT, command.state);
        assertTrue(command.active);
        assertFalse(command.timedOut);
    }

    @Test
    public void noWheelReversalInvariant_holdsForEveryActiveCommand() {
        ObstacleAvoidanceController veering = new ObstacleAvoidanceController();
        ObstacleAvoidanceController.Command veerCommand = veering.update(
                validReadingAt(ObstacleAvoidanceController.TRIGGER_DISTANCE_METERS - 0.05),
                0);
        assertBelowReversalRatio(veerCommand);

        ObstacleAvoidanceController reversing = new ObstacleAvoidanceController();
        ObstacleAvoidanceController.Command reverseCommand = reversing.update(
                validReadingAt(ObstacleAvoidanceController.CLOSE_RANGE_METERS - 0.10),
                0);
        assertBelowReversalRatio(reverseCommand);
    }

    private static void assertBelowReversalRatio(ObstacleAvoidanceController.Command command) {
        assertTrue(command.active);
        double limit = Math.abs(command.drive) * PointToPointController.MAX_TURN_TO_DRIVE_RATIO;
        assertTrue(
                "turn " + command.turn + " must stay under " + limit + " to avoid wheel reversal",
                Math.abs(command.turn) < limit);
    }
}
