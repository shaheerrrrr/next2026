package com.next2026.robotreset.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.next2026.robotreset.resolve.TargetSpec;

/**
 * {@code SharedPreferences}-backed source of truth for every UI target the
 * resolver needs to find, plus the four OpMode slot display names.
 *
 * <p>This class exists because of design-doc Risk 3: the Driver Station app's
 * resource-ids (and possibly its button text) are unverified guesses until
 * the Phase 4 hardware check happens. Every default below is exactly that — a
 * guess. When the real phone tells us a guess is wrong, the fix is
 * {@link #setOverride}, not a rebuild: {@link ConfigActivity} is the operator
 * surface for that.
 *
 * <p><b>Key scheme</b> (private to this class — nothing outside should know
 * the {@code SharedPreferences} file name or key names):
 * <ul>
 *   <li>Prefs file: {@code "robot_reset_target_config"}.</li>
 *   <li>Override kind: {@code "override_" + key + "_kind"} — stores
 *       {@link TargetSpec.Kind#name()}.</li>
 *   <li>Override value: {@code "override_" + key + "_value"}.</li>
 *   <li>Supported {@code key} values, matching the four spec getters:
 *       {@code "init"}, {@code "start"}, {@code "stop"},
 *       {@code "opmode_dropdown"}.</li>
 *   <li>OpMode slot text: {@code "opmode_slot_" + slotIndex + "_text"}.</li>
 * </ul>
 *
 * <p>Both key halves (kind + value) must be present for an override to take
 * effect; a partially-written override (e.g. app killed mid-write) is treated
 * as "no override" and falls back to the hardcoded default rather than
 * throwing.
 */
public final class TargetConfig {

    private TargetConfig() {
    }

    // Keys for the four overridable target specs. These are also the exact
    // strings callers must pass into setOverride().
    public static final String KEY_INIT = "init";
    public static final String KEY_START = "start";
    public static final String KEY_STOP = "stop";
    public static final String KEY_OPMODE_DROPDOWN = "opmode_dropdown";

    private static final String PREFS_NAME = "robot_reset_target_config";

    private static final String OPMODE_SCROLL_CONTAINER_VIEW_ID = "opmode_list_container";
    private static final int OPMODE_SLOT_COUNT = 4;

    // ---- Public API -------------------------------------------------------

    public static TargetSpec init(Context ctx) {
        return resolveSpec(prefs(ctx), KEY_INIT, TargetSpec.text("INIT"));
    }

    public static TargetSpec start(Context ctx) {
        return resolveSpec(prefs(ctx), KEY_START, TargetSpec.text("START"));
    }

    public static TargetSpec stop(Context ctx) {
        return resolveSpec(prefs(ctx), KEY_STOP, TargetSpec.text("STOP"));
    }

    public static TargetSpec opModeDropdown(Context ctx) {
        return resolveSpec(prefs(ctx), KEY_OPMODE_DROPDOWN, TargetSpec.text("Select OpMode"));
    }

    /**
     * Not currently overridable via {@link #setOverride} (no "opmode_scroll"
     * key is defined by the spec this class implements) — always the
     * hardcoded guess. Known likely-wrong; the OpMode selector that consumes
     * this has its own fallback for when it doesn't match anything on the
     * real DS app, so this class doesn't need to solve that.
     */
    public static TargetSpec opModeScrollContainer(Context ctx) {
        return TargetSpec.viewId(OPMODE_SCROLL_CONTAINER_VIEW_ID);
    }

    public static String opModeSlotText(Context ctx, int slotIndex) {
        return resolveSlotText(prefs(ctx), slotIndex);
    }

    public static void setOpModeSlotText(Context ctx, int slotIndex, String text) {
        writeSlotText(prefs(ctx), slotIndex, text);
    }

    /**
     * Persists an override for one of the four overridable target keys
     * ({@link #KEY_INIT}, {@link #KEY_START}, {@link #KEY_STOP},
     * {@link #KEY_OPMODE_DROPDOWN}). Takes effect immediately for subsequent
     * calls to the matching getter; no fallback spec is attached to a stored
     * override (the fallback chain in {@link TargetSpec} is a resolver-time
     * concept, not something this config layer currently persists).
     */
    public static void setOverride(Context ctx, String key, TargetSpec.Kind kind, String value) {
        writeOverride(prefs(ctx), key, kind, value);
    }

    // ---- Internals, package-private so they're directly unit-testable
    // without a real android.content.Context. ---------------------------

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static String overrideKindKey(String key) {
        return "override_" + key + "_kind";
    }

    static String overrideValueKey(String key) {
        return "override_" + key + "_value";
    }

    static String opModeSlotKey(int slotIndex) {
        return "opmode_slot_" + slotIndex + "_text";
    }

    static TargetSpec resolveSpec(SharedPreferences prefs, String key, TargetSpec defaultSpec) {
        String kindStr = prefs.getString(overrideKindKey(key), null);
        String value = prefs.getString(overrideValueKey(key), null);
        if (kindStr == null || value == null) {
            return defaultSpec;
        }
        TargetSpec.Kind kind;
        try {
            kind = TargetSpec.Kind.valueOf(kindStr);
        } catch (IllegalArgumentException e) {
            // Corrupt/unknown stored kind: fail safe to the hardcoded default
            // rather than throwing out of a getter every caller expects to
            // always succeed.
            return defaultSpec;
        }
        return kind == TargetSpec.Kind.VIEW_ID ? TargetSpec.viewId(value) : TargetSpec.text(value);
    }

    static String resolveSlotText(SharedPreferences prefs, int slotIndex) {
        String stored = prefs.getString(opModeSlotKey(slotIndex), null);
        return stored != null ? stored : ("OpMode Slot " + slotIndex);
    }

    static void writeSlotText(SharedPreferences prefs, int slotIndex, String text) {
        prefs.edit().putString(opModeSlotKey(slotIndex), text).apply();
    }

    static void writeOverride(SharedPreferences prefs, String key, TargetSpec.Kind kind, String value) {
        prefs.edit()
                .putString(overrideKindKey(key), kind.name())
                .putString(overrideValueKey(key), value)
                .apply();
    }

    /** Number of configurable OpMode slots (0..3), for UI iteration. */
    public static int opModeSlotCount() {
        return OPMODE_SLOT_COUNT;
    }

    /** The four overridable target keys, for UI iteration. */
    public static String[] overridableKeys() {
        return new String[] { KEY_INIT, KEY_START, KEY_STOP, KEY_OPMODE_DROPDOWN };
    }
}
