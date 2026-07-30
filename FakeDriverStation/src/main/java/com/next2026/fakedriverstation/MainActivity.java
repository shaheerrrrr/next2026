package com.next2026.fakedriverstation;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A realistic stand-in for the real FTC Driver Station app, built for
 * bench-testing the RobotReset AccessibilityService's element-resolution
 * path without needing the real DS app (or a real robot) installed.
 *
 * <p>Deliberately reproduces the shapes of the real app that the resolver
 * and harness both need to exercise:
 * <ul>
 *   <li>INIT starts disabled until an OpMode is selected (Gotcha 3 —
 *       clicking a disabled node must be a no-op, never retried).</li>
 *   <li>The OpMode list does not populate synchronously. Opening it
 *       schedules a short delay before the list is populated and made
 *       visible, forcing anything driving this UI to wait for an
 *       accessibility content-changed event rather than sleeping blindly.</li>
 *   <li>The OpMode list is longer than one screen, so some entries (the
 *       later OpMode slots) are off-screen on first render and require
 *       scrolling to find, exercising scroll-and-match rather than
 *       trivial first-screen matching.</li>
 * </ul>
 *
 * <p>Every button click and successful OpMode selection logs a distinctive
 * line under tag {@link #TAG} so the e2e harness can assert on outcomes via
 * logcat instead of UI inspection.
 */
public final class MainActivity extends Activity {

    public static final String TAG = "FakeDriverStation";

    /** Simulated dropdown inflation delay, matching real-world unpredictability. */
    private static final long OPMODE_LIST_POPULATE_DELAY_MS = 900;

    /**
     * OpMode list content. Deliberately longer than one screen and
     * interspersed with filler entries so that "OpMode Slot 2" and
     * "OpMode Slot 3" are NOT visible without scrolling, while
     * "OpMode Slot 0" and "OpMode Slot 1" are visible on first render.
     * This exercises both the trivial match path and the scroll-and-match
     * path in the same list.
     */
    private static final String[] OPMODE_ENTRIES = {
            "OpMode Slot 0",
            "Filler: Auto Red Sample",
            "Filler: Auto Blue Sample",
            "Filler: Teleop Field-Centric",
            "OpMode Slot 1",
            "Filler: Teleop Robot-Centric",
            "Filler: Calibration Routine",
            "Filler: Diagnostic Motor Test",
            "Filler: Diagnostic Servo Test",
            "Filler: Diagnostic IMU Test",
            "Filler: Diagnostic Camera Test",
            "Filler: Diagnostic Sensor Sweep",
            "Filler: Auto Park Only",
            "Filler: Auto Nothing",
            "OpMode Slot 2",
            "Filler: Legacy Teleop v1",
            "Filler: Legacy Teleop v2",
            "Filler: Practice Mode A",
            "Filler: Practice Mode B",
            "Filler: Practice Mode C",
            "Filler: Practice Mode D",
            "Filler: Bench Test Mode",
            "OpMode Slot 3",
            "Filler: End Of List Marker",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Button btnInit;
    private Button btnStart;
    private Button btnStop;
    private Button btnSelectOpMode;
    private ListView listOpMode;
    private TextView tvSelectedOpMode;

    private boolean opModeListPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnInit = findViewById(R.id.btn_init);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnSelectOpMode = findViewById(R.id.btn_select_opmode);
        listOpMode = findViewById(R.id.list_opmode);
        tvSelectedOpMode = findViewById(R.id.tv_selected_opmode);

        btnInit.setOnClickListener(v -> {
            // A disabled Button never dispatches onClick from a real touch,
            // and android:enabled="false" also makes the node non-clickable
            // to an AccessibilityService performing ACTION_CLICK, so this
            // listener only ever fires when INIT is genuinely enabled.
            Log.i(TAG, "INIT_CLICKED");
        });

        btnStart.setOnClickListener(v -> Log.i(TAG, "START_CLICKED"));

        btnStop.setOnClickListener(v -> Log.i(TAG, "STOP_CLICKED"));

        btnSelectOpMode.setOnClickListener(v -> openOpModeList());
    }

    private void openOpModeList() {
        if (opModeListPending) {
            return; // already loading, don't stack up repeat requests
        }
        if (listOpMode.getVisibility() == View.VISIBLE) {
            // Toggle closed if already open, mirroring a real dropdown.
            listOpMode.setVisibility(View.GONE);
            return;
        }

        opModeListPending = true;
        Log.i(TAG, "OPMODE_LIST_OPENING");

        // Deliberate artificial delay before the list populates/becomes
        // visible, simulating unpredictable real-world dropdown inflation
        // time. Anything driving this UI must wait for the
        // TYPE_WINDOW_CONTENT_CHANGED / TYPE_WINDOW_STATE_CHANGED
        // accessibility event fired below, not sleep blindly.
        handler.postDelayed(this::populateAndShowOpModeList, OPMODE_LIST_POPULATE_DELAY_MS);
    }

    private void populateAndShowOpModeList() {
        List<String> entries = new ArrayList<>();
        for (String entry : OPMODE_ENTRIES) {
            entries.add(entry);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, entries);
        listOpMode.setAdapter(adapter);
        listOpMode.setVisibility(View.VISIBLE);
        listOpMode.setOnItemClickListener((parent, view, position, id) -> {
            String selected = entries.get(position);
            onOpModeSelected(selected);
        });

        opModeListPending = false;

        // Explicitly fire a content-changed event on top of whatever the
        // framework sends automatically for the visibility/adapter change,
        // so this is reliably observable by an AccessibilityService
        // regardless of platform-version differences in when GONE->VISIBLE
        // + setAdapter auto-fires accessibility events.
        listOpMode.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        Log.i(TAG, "OPMODE_LIST_READY");
    }

    private void onOpModeSelected(String name) {
        listOpMode.setVisibility(View.GONE);
        tvSelectedOpMode.setText("Selected: " + name);
        btnInit.setEnabled(true);
        Log.i(TAG, "OPMODE_SELECTED:" + name);
    }
}
