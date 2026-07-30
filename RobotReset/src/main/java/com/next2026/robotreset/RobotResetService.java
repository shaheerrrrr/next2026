package com.next2026.robotreset;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

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

    private final Resolver resolver = new ResolverImpl();
    private final ResolutionLog log = new ResolutionLog();

    private UiTree uiTree;
    private OpModeSelector opModeSelector;

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
        super.onDestroy();
    }
}
