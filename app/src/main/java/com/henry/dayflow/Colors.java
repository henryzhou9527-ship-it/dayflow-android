package com.henry.dayflow;

import android.graphics.Color;

final class Colors {
    static final int BACKGROUND_TOP = Color.rgb(255, 250, 239);
    static final int BACKGROUND_BOTTOM = Color.rgb(255, 198, 141);
    static final int CARD = Color.argb(230, 255, 253, 248);
    static final int CARD_ALT = Color.argb(226, 255, 246, 238);
    static final int STROKE = Color.rgb(245, 216, 196);
    static final int TEXT = Color.rgb(45, 42, 39);
    static final int MUTED = Color.rgb(137, 132, 126);
    static final int ACCENT = Color.rgb(249, 110, 0);
    static final int ACCENT_SOFT = Color.rgb(255, 184, 128);
    static final int WORK = Color.rgb(106, 126, 255);
    static final int PERSONAL = Color.rgb(106, 173, 255);
    static final int COMMUNICATION = Color.rgb(255, 174, 140);
    static final int DISTRACTION = Color.rgb(255, 89, 80);
    static final int IDLE = Color.rgb(160, 174, 192);

    private Colors() {}

    static int colorForCategory(String category) {
        if (category == null) return ACCENT_SOFT;
        String normalized = category.trim().toLowerCase();
        if (normalized.contains("communication")) return COMMUNICATION;
        if (normalized.contains("personal")) return PERSONAL;
        if (normalized.contains("distraction")) return DISTRACTION;
        if (normalized.contains("idle")) return IDLE;
        return WORK;
    }
}
