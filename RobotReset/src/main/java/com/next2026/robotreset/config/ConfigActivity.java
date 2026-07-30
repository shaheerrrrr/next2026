package com.next2026.robotreset.config;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.next2026.robotreset.R;
import com.next2026.robotreset.resolve.TargetSpec;
import com.next2026.robotreset.ui.StatusActivity;

/**
 * The screen that turns a wrong Phase-4 hardware guess into a config change
 * instead of a rebuild (design doc Risk 3: DS app resource-id/text stability
 * across FTC SDK versions is unknown until the real phone is checked).
 *
 * <p>Two independent sections, both backed by {@link TargetConfig}:
 * <ul>
 *   <li>OpMode slot names (slots 0..3, matched by visible text in the
 *       OpMode list).</li>
 *   <li>Target overrides for INIT / START / STOP / OpMode-dropdown: view the
 *       current effective spec and set a new {@code TEXT} or {@code VIEW_ID}
 *       override.</li>
 * </ul>
 *
 * Kept usable rather than polished, per the brief: plain EditTexts, a
 * Spinner to pick which target to override, and a RadioGroup for the two
 * {@link TargetSpec.Kind} values.
 */
public final class ConfigActivity extends Activity {

    private final EditText[] slotEdits = new EditText[TargetConfig.opModeSlotCount()];

    private Spinner overrideKeySpinner;
    private TextView currentSpecText;
    private RadioGroup overrideKindGroup;
    private EditText overrideValueEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        buildSlotRows();

        overrideKeySpinner = findViewById(R.id.override_key_spinner);
        currentSpecText = findViewById(R.id.current_spec_text);
        overrideKindGroup = findViewById(R.id.override_kind_group);
        overrideValueEdit = findViewById(R.id.override_value_edit);
        Button saveSlotsButton = findViewById(R.id.save_slots_button);
        Button saveOverrideButton = findViewById(R.id.save_override_button);
        Button backToStatusButton = findViewById(R.id.back_to_status_button);

        ArrayAdapter<String> keyAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, TargetConfig.overridableKeys());
        keyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        overrideKeySpinner.setAdapter(keyAdapter);
        overrideKeySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshCurrentSpecText();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        saveSlotsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSlots();
            }
        });

        saveOverrideButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveOverride();
            }
        });

        backToStatusButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConfigActivity.this, StatusActivity.class));
            }
        });

        refreshCurrentSpecText();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Overrides may have changed via another route since onCreate (or
        // this is a fresh navigation back from Status); reflect the current
        // persisted state rather than stale values from creation time.
        reloadSlots();
        refreshCurrentSpecText();
    }

    private void buildSlotRows() {
        LinearLayout container = findViewById(R.id.opmode_slot_container);
        for (int i = 0; i < slotEdits.length; i++) {
            TextView label = new TextView(this);
            label.setText("Slot " + i + " (Ctrl+Alt+F" + (5 + i) + "):");

            EditText edit = new EditText(this);
            edit.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            edit.setText(TargetConfig.opModeSlotText(this, i));

            slotEdits[i] = edit;
            container.addView(label);
            container.addView(edit);
        }
    }

    private void reloadSlots() {
        for (int i = 0; i < slotEdits.length; i++) {
            slotEdits[i].setText(TargetConfig.opModeSlotText(this, i));
        }
    }

    private void saveSlots() {
        for (int i = 0; i < slotEdits.length; i++) {
            String text = slotEdits[i].getText().toString();
            TargetConfig.setOpModeSlotText(this, i, text);
        }
        Toast.makeText(this, "OpMode slot names saved", Toast.LENGTH_SHORT).show();
    }

    private void saveOverride() {
        String key = selectedKey();
        if (key == null) {
            return;
        }
        TargetSpec.Kind kind = overrideKindGroup.getCheckedRadioButtonId() == R.id.kind_view_id_radio
                ? TargetSpec.Kind.VIEW_ID
                : TargetSpec.Kind.TEXT;
        String value = overrideValueEdit.getText().toString();
        TargetConfig.setOverride(this, key, kind, value);
        refreshCurrentSpecText();
        Toast.makeText(this, "Override saved for " + key, Toast.LENGTH_SHORT).show();
    }

    private void refreshCurrentSpecText() {
        String key = selectedKey();
        if (key == null) {
            currentSpecText.setText("Current effective spec: (unknown)");
            return;
        }
        TargetSpec spec = currentSpecFor(key);
        currentSpecText.setText("Current effective spec: " + spec);
    }

    private String selectedKey() {
        Object selected = overrideKeySpinner.getSelectedItem();
        return selected == null ? null : selected.toString();
    }

    private TargetSpec currentSpecFor(String key) {
        if (TargetConfig.KEY_INIT.equals(key)) {
            return TargetConfig.init(this);
        } else if (TargetConfig.KEY_START.equals(key)) {
            return TargetConfig.start(this);
        } else if (TargetConfig.KEY_STOP.equals(key)) {
            return TargetConfig.stop(this);
        } else if (TargetConfig.KEY_OPMODE_DROPDOWN.equals(key)) {
            return TargetConfig.opModeDropdown(this);
        }
        return null;
    }
}
