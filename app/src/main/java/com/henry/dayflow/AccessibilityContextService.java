package com.henry.dayflow;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class AccessibilityContextService extends AccessibilityService {
    private static final String PREFS = "dayflow_accessibility_context";
    private static final String KEY_PACKAGE = "package_name";
    private static final String KEY_TITLE = "window_title";
    private static final String KEY_TEXT = "visible_text";
    private static final String KEY_UPDATED = "updated_at";
    private static final int MAX_TEXT_CHARS = 520;
    private static final int MAX_NODE_COUNT = 70;
    private static final long SNAPSHOT_TTL_MS = 5 * TimeUtil.MINUTE;

    static final class Snapshot {
        final String packageName;
        final String title;
        final String text;
        final long updatedAtMs;

        Snapshot(String packageName, String title, String text, long updatedAtMs) {
            this.packageName = packageName;
            this.title = title;
            this.text = text;
            this.updatedAtMs = updatedAtMs;
        }

        boolean hasContext() {
            return !isBlank(title) || !isBlank(text);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !new DayflowPrefs(this).captureAccessibilityContext()) {
            clear(this);
            return;
        }
        String packageName = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String title = eventTitle(event);
        String text = visibleText();
        save(this, new Snapshot(packageName, title, text, System.currentTimeMillis()));
    }

    @Override public void onInterrupt() {
    }

    static Intent settingsIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName component = new ComponentName(context, AccessibilityContextService.class);
        String full = component.flattenToString().toLowerCase(Locale.US);
        String shortName = component.flattenToShortString().toLowerCase(Locale.US);
        String normalized = enabled.toLowerCase(Locale.US);
        return normalized.contains(full) || normalized.contains(shortName);
    }

    static Snapshot latest(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long updated = prefs.getLong(KEY_UPDATED, 0L);
        if (updated <= 0L || System.currentTimeMillis() - updated > SNAPSHOT_TTL_MS) {
            return new Snapshot("", "", "", 0L);
        }
        return new Snapshot(
                prefs.getString(KEY_PACKAGE, ""),
                prefs.getString(KEY_TITLE, ""),
                prefs.getString(KEY_TEXT, ""),
                updated);
    }

    static void clear(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private static void save(Context context, Snapshot snapshot) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PACKAGE, clean(snapshot.packageName, 180))
                .putString(KEY_TITLE, clean(snapshot.title, 180))
                .putString(KEY_TEXT, clean(snapshot.text, MAX_TEXT_CHARS))
                .putLong(KEY_UPDATED, snapshot.updatedAtMs)
                .apply();
    }

    private static String eventTitle(AccessibilityEvent event) {
        if (event.getText() != null && !event.getText().isEmpty()) {
            String value = clean(String.valueOf(event.getText().get(0)), 180);
            if (!value.isEmpty()) return value;
        }
        CharSequence className = event.getClassName();
        return className == null ? "" : clean(className.toString(), 180);
    }

    private String visibleText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        try {
            StringBuilder out = new StringBuilder();
            collectText(root, out, new Counter(), new HashSet<String>());
            return clean(out.toString(), MAX_TEXT_CHARS);
        } finally {
            root.recycle();
        }
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out, Counter counter, Set<String> seen) {
        if (node == null || counter.value >= MAX_NODE_COUNT || out.length() >= MAX_TEXT_CHARS) return;
        counter.value++;
        append(out, node.getText(), seen);
        append(out, node.getContentDescription(), seen);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectText(child, out, counter, seen);
            } finally {
                child.recycle();
            }
        }
    }

    private static void append(StringBuilder out, CharSequence value, Set<String> seen) {
        if (value == null || out.length() >= MAX_TEXT_CHARS) return;
        String clean = clean(value.toString(), 140);
        if (clean.length() < 2 || seen.contains(clean)) return;
        seen.add(clean);
        if (out.length() > 0) out.append(" | ");
        out.append(clean);
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        while (cleaned.contains("  ")) cleaned = cleaned.replace("  ", " ");
        if (cleaned.length() <= max) return cleaned;
        return cleaned.substring(0, Math.max(1, max - 3)).trim() + "...";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class Counter {
        int value;
    }
}
