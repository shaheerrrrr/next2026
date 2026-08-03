package com.next2026.robotreset;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.next2026.robotreset.config.TargetConfig;
import com.next2026.robotreset.opmode.OpModeSelector;
import com.next2026.robotreset.resolve.AccessibilityUiTree;
import com.next2026.robotreset.resolve.Resolver;
import com.next2026.robotreset.resolve.ResolverImpl;
import com.next2026.robotreset.resolve.TargetSpec;
import com.next2026.robotreset.resolve.UiTree;
import com.next2026.robotreset.ui.ConsumeToggle;
import com.next2026.robotreset.ui.KeyEventLog;
import com.next2026.robotreset.ui.ResolutionLog;
import com.next2026.robotreset.ui.ServiceConnectionState;

/**
 * Intercepts the Ctrl+Alt+F1..F8 trigger chords and turns them into clicks on
 * the Driver Station app's own UI elements, resolved by view-id or visible
 * text rather than by screen coordinate.
 *
 * <p>The load-bearing property is that this <em>fails safe</em>: if the target
 * element isn't on screen, {@link Resolver} finds nothing and does nothing. A
 * coordinate tap, by contrast, always presses whatever happens to be at that
 * position — which is why the coordinate approach was built and then
 * deliberately reverted. See docs/design-accessibility-tap.md.
 *
 * <h3>Threading</h3>
 * Accessibility callbacks ({@link #onKeyEvent}, {@link #onAccessibilityEvent})
 * are delivered on this service's main thread, and resolution is performed
 * synchronously on that same thread. This is deliberate: {@link OpModeSelector}
 * is a single-threaded state machine with no internal synchronization, so
 * driving it from a background thread would be a data race. Node queries and
 * {@code ACTION_CLICK} are fast enough to run inline here; the alternative
 * (posting to a worker) would require making the selector thread-safe for no
 * real benefit.
 */
public final class RobotResetService extends AccessibilityService {

    /**
     * Single logcat tag for the whole app, so bench debugging is
     * {@code adb logcat -s RobotReset:V} (see tools/logcat-robotreset.ps1).
     * The in-app Key Monitor and Status screens are the no-cable equivalent.
     */
    public static final String TAG = "RobotReset";

    /**
     * TESTING HOOK (debug builds only): a live reference to the connected
     * service instance, set in {@link #onServiceConnected()} and cleared in
     * {@link #onDestroy()}. Exists solely so the debug-only
     * {@code DebugCommandReceiver} (src/debug/, absent from release builds --
     * see its class doc comment) can forward a broadcast into the exact same
     * {@link #handleCommand(ChordDecoder.Decoded)} path a real chord drives,
     * for emulator E2E testing (tools/e2e.ps1). {@code adb shell input}
     * cannot reach {@link #onKeyEvent} at all (it bypasses the accessibility
     * KeyboardInterceptor stage), so this is the seam that makes resolution
     * testable without a real USB HID keyboard. {@code volatile} because it
     * is written on the main thread but this static field is technically
     * reachable from any thread that holds a reference to the class.
     */
    private static volatile RobotResetService instance;

    private final Resolver resolver = new ResolverImpl();
    private final ResolutionLog log = new ResolutionLog();

    private UiTree uiTree;
    private OpModeSelector opModeSelector;

    /**
     * TESTING HOOK (debug builds only). Forwards to the live instance's
     * {@link #handleCommand(ChordDecoder.Decoded)} -- the identical code
     * path a real Ctrl+Alt+Fn chord drives from {@link #onKeyEvent} -- so a
     * debug-build test trigger cannot drift from production behavior. Logs
     * and returns harmlessly if the service isn't connected. See
     * {@code DebugCommandReceiver} for the only caller.
     */
    static void dispatchDebugCommand(ChordDecoder.Decoded decoded) {
        RobotResetService service = instance;
        if (service == null) {
            Log.w(TAG, "dispatchDebugCommand: no connected RobotResetService instance, dropping "
                    + decoded);
            return;
        }
        service.handleCommand(decoded);
    }

    /**
     * TESTING HOOK (debug builds only), separate from {@link #dispatchDebugCommand}:
     * logs every clickable node in the current window's tree (class, view-id,
     * text, content-description, bounds) instead of driving a chord. Exists
     * because {@code uiautomator dump} was found to be unreliable against the
     * Driver Station app's main screen -- it requires the UI to go idle, and
     * the app's own live-updating telemetry (ping/voltage) means it never
     * does, so the dump call fails outright. This bypasses that entirely by
     * reading {@link AccessibilityNodeInfo} directly, the same way {@link
     * com.next2026.robotreset.resolve.AccessibilityUiTree} does, since a
     * connected AccessibilityService has no "idle" requirement at all.
     */
    static void dispatchDebugDumpTree() {
        RobotResetService service = instance;
        if (service == null) {
            Log.w(TAG, "dispatchDebugDumpTree: no connected RobotResetService instance");
            return;
        }
        service.dumpClickableNodes();
    }

    private void dumpClickableNodes() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.w(TAG, "dumpClickableNodes: getRootInActiveWindow() returned null");
            return;
        }
        Log.i(TAG, "dumpClickableNodes: begin");
        dumpNode(root, 0);
        Log.i(TAG, "dumpClickableNodes: end");
    }

    private void dumpNode(AccessibilityNodeInfo node, int depth) {
        if (node == null) {
            return;
        }
        if (node.isClickable()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            Log.i(TAG, "CLICKABLE depth=" + depth
                    + " class=" + node.getClassName()
                    + " viewId=" + node.getViewIdResourceName()
                    + " text=" + node.getText()
                    + " desc=" + node.getContentDescription()
                    + " enabled=" + node.isEnabled()
                    + " bounds=" + bounds);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            dumpNode(node.getChild(i), depth + 1);
        }
    }

    /**
     * TESTING HOOK (debug builds only): clicks the Nth clickable node found
     * by the exact same depth-first walk {@link #dumpClickableNodes()} uses
     * (so the index printed alongside a CLICKABLE log line is stable and
     * usable here), via the same {@code ACTION_ACCESSIBILITY_FOCUS} +
     * {@code ACTION_CLICK} sequence production clicks use. Exists to
     * empirically test which of several nodes sharing ambiguous/absent
     * text, description, and view-id is the one actually wired to real app
     * logic -- this is diagnostic only; it does not become part of
     * production resolution, which still only ever clicks by text/view-id
     * match, never by position/index.
     */
    static void dispatchDebugClickIndex(int targetIndex) {
        RobotResetService service = instance;
        if (service == null) {
            Log.w(TAG, "dispatchDebugClickIndex: no connected RobotResetService instance");
            return;
        }
        service.clickNodeByIndex(targetIndex);
    }

    private void clickNodeByIndex(int targetIndex) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.w(TAG, "clickNodeByIndex: getRootInActiveWindow() returned null");
            return;
        }
        int[] counter = {0};
        AccessibilityNodeInfo found = findNodeByIndex(root, targetIndex, counter);
        if (found == null) {
            Log.w(TAG, "clickNodeByIndex: index " + targetIndex + " not found, only "
                    + counter[0] + " clickable node(s) present");
            return;
        }
        found.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        boolean clicked = found.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.i(TAG, "clickNodeByIndex: index=" + targetIndex + " clicked=" + clicked
                + " viewId=" + found.getViewIdResourceName()
                + " desc=" + found.getContentDescription());
    }

    private AccessibilityNodeInfo findNodeByIndex(AccessibilityNodeInfo node, int targetIndex, int[] counter) {
        if (node == null) {
            return null;
        }
        if (node.isClickable()) {
            if (counter[0] == targetIndex) {
                return node;
            }
            counter[0]++;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo result = findNodeByIndex(node.getChild(i), targetIndex, counter);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        // GOTCHA 1 (docs/design-accessibility-tap.md, docs/robot-reset-app-brief.md):
        // declaring canRequestFilterKeyEvents in the XML config is NOT enough.
        // onKeyEvent silently never fires without also setting this flag here.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }

        // AccessibilityUiTree re-queries getRootInActiveWindow() on every call,
        // so one instance stays valid for the life of the service.
        uiTree = new AccessibilityUiTree(this);
        opModeSelector = new OpModeSelector(resolver, log);

        ServiceConnectionState.setConnected(true);
        instance = this;
        Log.i(TAG, "service connected; key event filtering requested");
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int metaState = event.getMetaState();

        ChordDecoder.Decoded decoded = ChordDecoder.decode(keyCode, metaState);
        boolean matched = decoded.command != ChordDecoder.Command.NONE;
        boolean consumed = matched && ConsumeToggle.isConsumeOnMatch();

        // onKeyEvent fires for ACTION_DOWN *and* ACTION_UP, and again for each
        // auto-repeat while a key is held. Act only on the initial down, or a
        // single chord press would fire the command two or more times — which
        // for STOP/INIT means real duplicate robot commands, not a cosmetic
        // bug. Consumption still applies to every phase, otherwise the DS app
        // would receive an orphaned key-up for a down it never saw.
        boolean isInitialDown = event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0;

        KeyEventLog.add(keyCode, metaState, decoded.command, consumed);
        Log.d(TAG, "onKeyEvent keyCode=" + keyCode
                + " meta=0x" + Integer.toHexString(metaState)
                + " action=" + event.getAction()
                + " repeat=" + event.getRepeatCount()
                + " decoded=" + decoded.command
                + " slot=" + decoded.slotIndex
                + " consumed=" + consumed);

        if (matched && isInitialDown) {
            // Act on every match regardless of the consume toggle: that toggle
            // exists only to test event consumption independently of
            // resolution, and it would be confusing for it to silently disable
            // the app's actual function.
            handleCommand(decoded);
        }

        return consumed;
    }

    private void handleCommand(ChordDecoder.Decoded decoded) {
        if (uiTree == null || opModeSelector == null) {
            // Callbacks can only arrive after onServiceConnected, so this is
            // defensive only.
            return;
        }

        switch (decoded.command) {
            case INIT:
                resolver.resolveAndClick(uiTree, TargetConfig.init(this), log);
                break;
            case START:
                resolver.resolveAndClick(uiTree, TargetConfig.start(this), log);
                break;
            case STOP:
                resolver.resolveAndClick(uiTree, TargetConfig.stop(this), log);
                break;
            case OPMODE_SLOT:
                startOpModeSelection(decoded.slotIndex);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void startOpModeSelection(int slotIndex) {
        String targetText = TargetConfig.opModeSlotText(this, slotIndex);
        TargetSpec dropdown = TargetConfig.opModeDropdown(this);
        TargetSpec scrollContainer = TargetConfig.opModeScrollContainer(this);
        opModeSelector.begin(uiTree, dropdown, scrollContainer, targetText);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Drives the OpMode dropdown's open -> wait -> match -> scroll
        // sequence. The selector ignores events unless a selection is actually
        // in flight, and it filters event types itself, so this stays a
        // straight hand-off. Waiting on the event (rather than sleeping) is
        // required because dropdown inflation time is not predictable.
        if (opModeSelector != null) {
            opModeSelector.onAccessibilityEvent(event, uiTree);
        }
    }

    @Override
    public void onInterrupt() {
        // No-op.
    }

    @Override
    public void onDestroy() {
        ServiceConnectionState.setConnected(false);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
