package com.henry.dayflow;

import android.content.Context;
import android.graphics.Typeface;

final class DayflowType {
    private static Typeface sans;
    private static Typeface serif;

    private DayflowType() {}

    static Typeface sans(Context context) {
        if (sans == null) {
            sans = Typeface.createFromAsset(context.getAssets(), "fonts/figtree.ttf");
        }
        return sans;
    }

    static Typeface sans(Context context, boolean bold) {
        Typeface base = sans(context);
        return bold ? Typeface.create(base, Typeface.BOLD) : base;
    }

    static Typeface serif(Context context) {
        if (serif == null) {
            serif = Typeface.createFromAsset(context.getAssets(), "fonts/instrument_serif.ttf");
        }
        return serif;
    }
}
