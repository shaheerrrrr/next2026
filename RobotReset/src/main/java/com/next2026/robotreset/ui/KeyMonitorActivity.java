package com.next2026.robotreset.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import com.next2026.robotreset.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Step 0's bench instrument (see docs/robot-reset-app-brief.md section 7).
 * Shows a live log of key events RobotResetService has seen, a
 * consume/pass-through toggle, and a simple "is the accessibility service
 * actually running" indicator. This is the launcher activity.
 */
public final class KeyMonitorActivity extends Activity implements KeyEventLog.Listener {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView serviceStatusText;
    private ListView keyEventList;
    private ArrayAdapter<String> adapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            updateServiceStatusText();
            mainHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_key_monitor);

        serviceStatusText = findViewById(R.id.service_status_text);
        keyEventList = findViewById(R.id.key_event_list);
        Switch consumeSwitch = findViewById(R.id.consume_switch);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        keyEventList.setAdapter(adapter);

        consumeSwitch.setChecked(ConsumeToggle.isConsumeOnMatch());
        consumeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ConsumeToggle.setConsumeOnMatch(isChecked);
            }
        });

        refreshLogList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeyEventLog.addListener(this);
        refreshLogList();
        updateServiceStatusText();
        mainHandler.post(statusPoller);
    }

    @Override
    protected void onPause() {
        super.onPause();
        KeyEventLog.removeListener(this);
        mainHandler.removeCallbacks(statusPoller);
    }

    @Override
    public void onEntryAdded(KeyEventLog.Entry entry) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                refreshLogList();
            }
        });
    }

    private void updateServiceStatusText() {
        boolean connected = ServiceConnectionState.isConnected();
        serviceStatusText.setText(connected
                ? "Accessibility service: RUNNING"
                : "Accessibility service: NOT running (enable in Settings > Accessibility)");
    }

    private void refreshLogList() {
        List<KeyEventLog.Entry> entries = KeyEventLog.getAll();
        adapter.clear();
        for (KeyEventLog.Entry entry : entries) {
            adapter.add(formatEntry(entry));
        }
    }

    private static String formatEntry(KeyEventLog.Entry entry) {
        return String.format(Locale.US, "%s  keyCode=%d  meta=0x%08X  cmd=%s  consumed=%s",
                TIME_FORMAT.format(entry.timestamp),
                entry.keyCode,
                entry.metaState,
                entry.command,
                entry.consumed ? "y" : "n");
    }
}
