package org.firstinspires.ftc.teamcode.navigation;

/**
 * Roomba-style wall-hugging obstacle avoidance driven by one forward-facing ultrasonic sensor.
 * Below {@link #TRIGGER_DISTANCE_METERS} this takes over from GPS navigation and veers right
 * until clear; something too close for a forward arc to clear instead reverses in a left arc,
 * since this robot cannot pivot in place (see {@link org.firstinspires.ftc.teamcode.subsystems.Drivetrain}).
 */
public final class ObstacleAvoidanceController {
    public static final double TRIGGER_DISTANCE_METERS   = 1.25; // primary configurable knob per user request
    public static final double CLEAR_DISTANCE_METERS     = 1.50; // hysteresis: must clear past here, not just past trigger
    public static final double CLOSE_RANGE_METERS        = 0.45; // below this, forward arc can't clear it; reverse-left instead
    public static final double CLOSE_RANGE_CLEAR_METERS  = 0.60; // separate exit threshold so close-range sub-state doesn't chatter

    public static final double VEER_DRIVE_POWER    = 0.55;
    public static final double VEER_TURN_POWER     = 0.42;  // ratio 0.42/0.55 ~= 0.76, stays under 0.78 so no wheel reversal
    public static final double REVERSE_DRIVE_POWER = 0.50;
    public static final double REVERSE_TURN_POWER  = 0.35;  // ratio 0.35/0.50 = 0.70, stays under 0.78
    public static final double REVERSE_TURN_SIGN   = 1.0;   // single flip point if a wheels-raised test shows the arc direction is backwards

    public static final long MAX_AVOIDANCE_MS = 6_000; // 0 disables the timeout

    public enum State { INACTIVE, VEERING_RIGHT, REVERSING_LEFT }

    private State state = State.INACTIVE;
    private long activeSinceMs;
    private int consecutiveFaults;

    public void reset() {
        state = State.INACTIVE;
        activeSinceMs = 0;
        consecutiveFaults = 0;
    }

    public Command update(UltrasonicReading reading, long nowMs) {
        // Fail open: a bad I2C transaction and a valid "no echo" frame both read as clear. Only
        // packetValid() feeds the fault counter, and that counter is diagnostic-only — it must
        // never gate the fail-open behavior below.
        if (!reading.packetValid()) {
            consecutiveFaults++;
        } else {
            consecutiveFaults = 0;
        }

        boolean clear = !reading.hasDistance();
        double distance = reading.distanceMeters();

        if (state == State.INACTIVE) {
            if (!clear && distance < CLOSE_RANGE_METERS) {
                enterActive(State.REVERSING_LEFT, nowMs);
            } else if (!clear && distance < TRIGGER_DISTANCE_METERS) {
                enterActive(State.VEERING_RIGHT, nowMs);
            } else {
                return Command.inactive(state, consecutiveFaults, distance);
            }
        } else {
            if (clear) {
                // Fail open: no usable reading while active hands control straight back to GPS.
                state = State.INACTIVE;
                return Command.inactive(state, consecutiveFaults, distance);
            }

            if (state == State.VEERING_RIGHT) {
                if (distance >= CLEAR_DISTANCE_METERS) {
                    state = State.INACTIVE;
                    return Command.inactive(state, consecutiveFaults, distance);
                } else if (distance < CLOSE_RANGE_METERS) {
                    state = State.REVERSING_LEFT;
                }
            } else { // REVERSING_LEFT
                if (distance >= CLOSE_RANGE_CLEAR_METERS) {
                    state = State.VEERING_RIGHT;
                }
            }
        }

        if (MAX_AVOIDANCE_MS != 0 && nowMs - activeSinceMs > MAX_AVOIDANCE_MS) {
            state = State.INACTIVE;
            return Command.timedOut(consecutiveFaults, distance);
        }

        return state == State.VEERING_RIGHT
                ? Command.veerRight(consecutiveFaults, distance)
                : Command.reverseLeft(consecutiveFaults, distance);
    }

    private void enterActive(State newState, long nowMs) {
        state = newState;
        activeSinceMs = nowMs;
    }

    public static final class Command {
        public final boolean active;          // true => caller must use this drive/turn instead of GPS output
        public final double  drive;           // conceptual frame: >0 forward
        public final double  turn;            // conceptual frame: >0 nose-right
        public final State   state;
        public final boolean timedOut;        // true once MAX_AVOIDANCE_MS continuous active time is exceeded
        public final String  phase;           // human-readable phase description for telemetry/logging
        public final int     consecutiveFaults; // count of consecutive !packetValid() reads, diagnostic only
        public final double  distanceMeters;  // NaN when no usable reading (mirrors UltrasonicReading.distanceMeters())

        Command(
                boolean active,
                double drive,
                double turn,
                State state,
                boolean timedOut,
                String phase,
                int consecutiveFaults,
                double distanceMeters) {
            this.active = active;
            this.drive = drive;
            this.turn = turn;
            this.state = state;
            this.timedOut = timedOut;
            this.phase = phase;
            this.consecutiveFaults = consecutiveFaults;
            this.distanceMeters = distanceMeters;
        }

        public static Command inactive(State state, int consecutiveFaults, double distanceMeters) {
            return new Command(
                    false,
                    0,
                    0,
                    state,
                    false,
                    "Clear; GPS navigation has control",
                    consecutiveFaults,
                    distanceMeters);
        }

        public static Command veerRight(int consecutiveFaults, double distanceMeters) {
            return new Command(
                    true,
                    VEER_DRIVE_POWER,
                    VEER_TURN_POWER,
                    State.VEERING_RIGHT,
                    false,
                    "Obstacle ahead; veering right",
                    consecutiveFaults,
                    distanceMeters);
        }

        public static Command reverseLeft(int consecutiveFaults, double distanceMeters) {
            return new Command(
                    true,
                    -REVERSE_DRIVE_POWER,
                    REVERSE_TURN_SIGN * REVERSE_TURN_POWER,
                    State.REVERSING_LEFT,
                    false,
                    "Too close to veer; reversing left",
                    consecutiveFaults,
                    distanceMeters);
        }

        public static Command timedOut(int consecutiveFaults, double distanceMeters) {
            return new Command(
                    false,
                    0,
                    0,
                    State.INACTIVE,
                    true,
                    "Avoidance timed out; bailing to manual control",
                    consecutiveFaults,
                    distanceMeters);
        }
    }
}
