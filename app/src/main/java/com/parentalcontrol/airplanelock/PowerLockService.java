package com.parentalcontrol.airplanelock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashSet;
import java.util.Set;

public class PowerLockService extends AccessibilityService {

    // Only inspect events from these packages (fast set lookup)
    private static final Set<String> POWER_MENU_PACKAGES = new HashSet<>();
    static {
        POWER_MENU_PACKAGES.add("com.android.systemui");
        POWER_MENU_PACKAGES.add("android");
    }

    private static final String[] POWER_KEYWORDS = {
            "power off", "shut down", "shutdown", "turn off", "reboot", "restart"
    };

    // Max depth for accessibility tree search — keeps CPU use bounded
    private static final int MAX_DEPTH = 5;

    // Debounce: only trigger once per 2 seconds
    private static final long DEBOUNCE_MS = 2000;
    private long lastTriggerTime = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only care about window state changes (power menu opening), not content changes
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null || !POWER_MENU_PACKAGES.contains(pkg.toString())) return;

        // Debounce — don't re-trigger within 2 seconds
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < DEBOUNCE_MS) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            if (containsPowerKeyword(root, 0)) {
                lastTriggerTime = now;
                triggerPasswordScreen();
            }
        } finally {
            root.recycle();
        }
    }

    private boolean containsPowerKeyword(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) return false;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String textLower = text != null ? text.toString().toLowerCase() : "";
        String descLower = desc != null ? desc.toString().toLowerCase() : "";

        for (String kw : POWER_KEYWORDS) {
            if (textLower.contains(kw) || descLower.contains(kw)) return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean found = containsPowerKeyword(child, depth + 1);
            child.recycle();
            if (found) return true;
        }
        return false;
    }

    private void triggerPasswordScreen() {
        if (PasswordActivity.isShowing) return;

        SharedPreferences prefs = getSharedPreferences("AirplaneLockPrefs", MODE_PRIVATE);
        if (prefs.getBoolean("bypass_active", false)) return;
        if (TextUtils.isEmpty(prefs.getString("hashed_password", ""))) return;

        AccessLog.record(this, AccessLog.EVENT_POWER_BLOCKED);

        Intent intent = new Intent(this, PasswordActivity.class);
        intent.putExtra(PasswordActivity.EXTRA_TYPE, PasswordActivity.TYPE_POWER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        // Only TYPE_WINDOW_STATE_CHANGED — removed TYPE_WINDOW_CONTENT_CHANGED (too frequent)
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 500; // 500ms instead of 100ms — 5x fewer callbacks
        setServiceInfo(info);
    }
}
