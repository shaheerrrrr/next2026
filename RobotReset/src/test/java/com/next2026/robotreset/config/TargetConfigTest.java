package com.next2026.robotreset.config;

import android.content.SharedPreferences;

import com.next2026.robotreset.resolve.TargetSpec;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Plain JVM unit tests for {@link TargetConfig}'s override/default logic.
 *
 * <p><b>Coverage note:</b> {@link TargetConfig}'s public API takes an
 * {@code android.content.Context}. {@code Context} is an abstract class with
 * a very large method surface (not an interface), so hand-rolling a fake is
 * impractical, and this module has no Mockito/Robolectric dependency wired
 * up (adding one means editing {@code build.gradle}, which is out of scope
 * for this lane). To get genuine, non-trivial JVM coverage anyway,
 * {@code TargetConfig} factors its logic into package-private static helpers
 * ({@code resolveSpec}, {@code resolveSlotText}, {@code writeOverride},
 * {@code writeSlotText}, and the key-naming helpers) that take
 * {@code android.content.SharedPreferences} directly -- which *is* a plain
 * interface, trivially fakeable below. Every public getter/setter in
 * {@code TargetConfig} is a one-line pass-through: resolve
 * {@code SharedPreferences} from the {@code Context}, then call one of these
 * tested helpers. That one line ({@code ctx.getSharedPreferences(...)}) is
 * the only thing this test suite does not exercise.
 */
public class TargetConfigTest {

    // ---- default / override precedence -------------------------------

    @Test
    public void resolveSpec_returnsDefault_whenNoOverrideStored() {
        FakePrefs prefs = new FakePrefs();
        TargetSpec result = TargetConfig.resolveSpec(prefs, TargetConfig.KEY_INIT, TargetSpec.text("INIT"));
        assertEquals(TargetSpec.Kind.TEXT, result.kind);
        assertEquals("INIT", result.value);
    }

    @Test
    public void resolveSpec_returnsStoredTextOverride() {
        FakePrefs prefs = new FakePrefs();
        TargetConfig.writeOverride(prefs, TargetConfig.KEY_INIT, TargetSpec.Kind.TEXT, "Begin");

        TargetSpec result = TargetConfig.resolveSpec(prefs, TargetConfig.KEY_INIT, TargetSpec.text("INIT"));
        assertEquals(TargetSpec.Kind.TEXT, result.kind);
        assertEquals("Begin", result.value);
    }

    @Test
    public void resolveSpec_returnsStoredViewIdOverride() {
        FakePrefs prefs = new FakePrefs();
        TargetConfig.writeOverride(prefs, TargetConfig.KEY_START, TargetSpec.Kind.VIEW_ID, "btn_start");

        TargetSpec result = TargetConfig.resolveSpec(prefs, TargetConfig.KEY_START, TargetSpec.text("START"));
        assertEquals(TargetSpec.Kind.VIEW_ID, result.kind);
        assertEquals("btn_start", result.value);
    }

    @Test
    public void resolveSpec_fallsBackToDefault_whenOverridePartiallyWritten() {
        // Simulates e.g. process death mid-write: only the kind half landed.
        FakePrefs prefs = new FakePrefs();
        prefs.edit().putString(TargetConfig.overrideKindKey(TargetConfig.KEY_STOP),
                TargetSpec.Kind.VIEW_ID.name()).apply();

        TargetSpec result = TargetConfig.resolveSpec(prefs, TargetConfig.KEY_STOP, TargetSpec.text("STOP"));
        assertEquals(TargetSpec.Kind.TEXT, result.kind);
        assertEquals("STOP", result.value);
    }

    @Test
    public void resolveSpec_fallsBackToDefault_whenStoredKindIsCorrupt() {
        FakePrefs prefs = new FakePrefs();
        prefs.edit()
                .putString(TargetConfig.overrideKindKey(TargetConfig.KEY_STOP), "NOT_A_REAL_KIND")
                .putString(TargetConfig.overrideValueKey(TargetConfig.KEY_STOP), "whatever")
                .apply();

        TargetSpec result = TargetConfig.resolveSpec(prefs, TargetConfig.KEY_STOP, TargetSpec.text("STOP"));
        assertEquals(TargetSpec.Kind.TEXT, result.kind);
        assertEquals("STOP", result.value);
    }

    @Test
    public void overrideForOneKey_doesNotAffectAnother() {
        FakePrefs prefs = new FakePrefs();
        TargetConfig.writeOverride(prefs, TargetConfig.KEY_INIT, TargetSpec.Kind.TEXT, "Begin");

        TargetSpec stopResult = TargetConfig.resolveSpec(prefs, TargetConfig.KEY_STOP, TargetSpec.text("STOP"));
        assertEquals("STOP", stopResult.value);
    }

    // ---- opmode slot text ----------------------------------------------

    @Test
    public void resolveSlotText_returnsFormattedDefault_whenNoOverride() {
        FakePrefs prefs = new FakePrefs();
        assertEquals("OpMode Slot 0", TargetConfig.resolveSlotText(prefs, 0));
        assertEquals("OpMode Slot 3", TargetConfig.resolveSlotText(prefs, 3));
    }

    @Test
    public void resolveSlotText_returnsStoredOverride() {
        FakePrefs prefs = new FakePrefs();
        TargetConfig.writeSlotText(prefs, 1, "Blue Auto Left");

        assertEquals("Blue Auto Left", TargetConfig.resolveSlotText(prefs, 1));
        // Other slots unaffected.
        assertEquals("OpMode Slot 2", TargetConfig.resolveSlotText(prefs, 2));
    }

    // ---- key scheme, exact strings matter for other lanes' harnesses ----

    @Test
    public void keyScheme_matchesSpecifiedShape() {
        assertEquals("override_init_kind", TargetConfig.overrideKindKey("init"));
        assertEquals("override_init_value", TargetConfig.overrideValueKey("init"));
        assertEquals("override_opmode_dropdown_kind", TargetConfig.overrideKindKey(TargetConfig.KEY_OPMODE_DROPDOWN));
        assertEquals("opmode_slot_0_text", TargetConfig.opModeSlotKey(0));
    }

    @Test
    public void supportedOverrideKeys_matchSpec() {
        Set<String> keys = new HashSet<>();
        for (String k : TargetConfig.overridableKeys()) {
            keys.add(k);
        }
        assertTrue(keys.contains("init"));
        assertTrue(keys.contains("start"));
        assertTrue(keys.contains("stop"));
        assertTrue(keys.contains("opmode_dropdown"));
        assertEquals(4, keys.size());
    }

    // ---- hand-rolled fake, SharedPreferences is a plain interface so
    // this needs no mocking framework. Only get/put String are exercised
    // by TargetConfig; other methods throw if ever called, so a test that
    // hits them fails loudly instead of silently no-op'ing. ---------------

    private static final class FakePrefs implements SharedPreferences {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getString(String key, String defValue) {
            return values.containsKey(key) ? values.get(key) : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getInt(String key, int defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLong(String key, long defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float getFloat(String key, float defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor(this);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            // no-op: not exercised by TargetConfig.
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            // no-op: not exercised by TargetConfig.
        }
    }

    private static final class FakeEditor implements SharedPreferences.Editor {
        private final FakePrefs prefs;
        private final Map<String, String> pending = new HashMap<>();

        FakeEditor(FakePrefs prefs) {
            this.prefs = prefs;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            pending.put(key, null);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            prefs.values.clear();
            return this;
        }

        @Override
        public boolean commit() {
            apply();
            return true;
        }

        @Override
        public void apply() {
            for (Map.Entry<String, String> e : pending.entrySet()) {
                if (e.getValue() == null) {
                    prefs.values.remove(e.getKey());
                } else {
                    prefs.values.put(e.getKey(), e.getValue());
                }
            }
        }
    }
}
