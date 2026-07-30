package com.next2026.robotreset.ui;

/**
 * Static flag flipped by {@code RobotResetService.onServiceConnected}/
 * {@code onDestroy}, polled by {@code KeyMonitorActivity} as a simple visual
 * "is the accessibility service actually running" indicator. Service
 * enablement is not otherwise observable (see design doc risk #4) and nobody
 * will be standing at this phone once it's deployed, so a pre-session visual
 * check matters.
 */
public final class ServiceConnectionState {

    private ServiceConnectionState() {
    }

    private static volatile boolean connected = false;

    public static boolean isConnected() {
        return connected;
    }

    public static void setConnected(boolean value) {
        connected = value;
    }
}
