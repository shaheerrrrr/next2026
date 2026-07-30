package com.next2026.robotreset.resolve;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts an {@link AccessibilityService}'s active window to the {@link UiTree}
 * contract, via {@link AccessibilityService#getRootInActiveWindow()}.
 */
public final class AccessibilityUiTree implements UiTree {

    private final AccessibilityService service;

    public AccessibilityUiTree(AccessibilityService service) {
        this.service = service;
    }

    @Override
    public NodeRef getRoot() {
        return AccessibilityNodeRefImpl.wrap(service.getRootInActiveWindow());
    }

    @Override
    public List<NodeRef> findByViewId(String viewId) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return new ArrayList<>();
        }
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(viewId);
        return wrapAll(found);
    }

    @Override
    public List<NodeRef> findByText(CharSequence text) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return new ArrayList<>();
        }
        List<AccessibilityNodeInfo> found =
                root.findAccessibilityNodeInfosByText(text == null ? null : text.toString());
        return wrapAll(found);
    }

    private static List<NodeRef> wrapAll(List<AccessibilityNodeInfo> nodes) {
        List<NodeRef> result = new ArrayList<>();
        if (nodes == null) {
            return result;
        }
        for (AccessibilityNodeInfo node : nodes) {
            NodeRef ref = AccessibilityNodeRefImpl.wrap(node);
            if (ref != null) {
                result.add(ref);
            }
        }
        return result;
    }
}
