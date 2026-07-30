package com.next2026.robotreset.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.next2026.robotreset.R;
import com.next2026.robotreset.config.ConfigActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pre-session check an operator runs to confirm the accessibility
 * service is actually alive and see what it has been doing. Nobody is at
 * this phone once it's deployed (design doc Risk 4: service enablement is
 * not remotely observable), so this screen — read at the bench before a
 * session starts — is the only visibility that exists.
 *
 * <p>Structurally mirrors {@code KeyMonitorActivity}'s polling pattern
 * ({@link ServiceConnectionState#isConnected()} on a 1s handler loop) but
 * shows {@link ResolutionLog} entries (what got clicked and why/why not)
 * rather than {@code KeyEventLog}'s raw key events.
 */
public final class StatusActivity extends Activity implements ResolutionLog.Listener {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView serviceStatusText;
    private ListView resolutionLogList;
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
        setContentView(R.layout.activity_status);

        serviceStatusText = findViewById(R.id.service_status_text);
        resolutionLogList = findViewById(R.id.resolution_log_list);
        Button openConfigButton = findViewById(R.id.open_config_button);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        resolutionLogList.setAdapter(adapter);

        openConfigButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(StatusActivity.this, ConfigActivity.class));
            }
        });

        refreshLogList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ResolutionLog.addListener(this);
        refreshLogList();
        updateServiceStatusText();
        mainHandler.post(statusPoller);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ResolutionLog.removeListener(this);
        mainHandler.removeCallbacks(statusPoller);
    }

    @Override
    public void onEntryAdded(ResolutionLog.Entry entry) {
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
        List<ResolutionLog.Entry> entries = ResolutionLog.getAll();
        adapter.clear();
        for (ResolutionLog.Entry entry : entries) {
            adapter.add(formatEntry(entry));
        }
    }

    private static String formatEntry(ResolutionLog.Entry entry) {
        return String.format(Locale.US, "%s  %-16s clicked=%s  %s",
                TIME_FORMAT.format(entry.timestamp),
                entry.label,
                entry.clicked ? "y" : "n",
                entry.reason);
    }
}
