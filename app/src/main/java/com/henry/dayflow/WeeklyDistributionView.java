package com.henry.dayflow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WeeklyDistributionView extends View {
    private static final int OTHER_COLOR = android.graphics.Color.rgb(191, 182, 174);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Item> items = new ArrayList<>();
    private List<TimelineCard> cards = new ArrayList<>();
    private long totalMs;
    private String selectedKey;
    private RectF donutRect = new RectF();

    WeeklyDistributionView(Context context) {
        super(context);
        setMinimumHeight(dp(300));
        setWillNotDraw(false);
    }

    void setCards(List<TimelineCard> cards) {
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        selectedKey = null;
        rebuild();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPanel(canvas, 0, 0, getWidth(), getHeight());
        drawSerif(canvas, "Weekly distribution", dp(18), dp(38), dp(21), Colors.ACCENT);

        float donutSize = Math.min(dp(205), Math.max(dp(138), getWidth() * 0.43f));
        float donutLeft = dp(18);
        float donutTop = dp(58);
        donutRect.set(donutLeft, donutTop, donutLeft + donutSize, donutTop + donutSize);
        drawDonut(canvas, donutRect);
        drawLegend(canvas, donutRect.right + dp(18), donutTop + dp(8), getWidth() - donutRect.right - dp(32));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        if (items.isEmpty()) return true;
        float donutSize = donutRect.width();
        float legendX = donutRect.right + dp(18);
        float legendY = donutRect.top + dp(8);
        for (int i = 0; i < items.size(); i++) {
            RectF hit = new RectF(legendX - dp(8), legendY + i * dp(34) - dp(18), getWidth() - dp(10), legendY + i * dp(34) + dp(10));
            if (hit.contains(event.getX(), event.getY())) {
                selectedKey = items.get(i).key.equals(selectedKey) ? null : items.get(i).key;
                invalidate();
                return true;
            }
        }
        float dx = event.getX() - donutRect.centerX();
        float dy = event.getY() - donutRect.centerY();
        if (Math.sqrt(dx * dx + dy * dy) <= donutSize / 2f) {
            selectedKey = null;
            invalidate();
        }
        return true;
    }

    private void rebuild() {
        items.clear();
        totalMs = 0;
        Map<String, Item> byKey = new LinkedHashMap<>();
        for (TimelineCard card : cards) {
            if (isSystem(card) || card.durationMs() <= 0) continue;
            String name = clean(card.category, "Uncategorized");
            String key = normalized(name);
            Item item = byKey.get(key);
            if (item == null) {
                item = new Item(key, name, Colors.colorForCategory(name));
                byKey.put(key, item);
            }
            item.durationMs += card.durationMs();
            totalMs += card.durationMs();
        }
        List<Item> sorted = new ArrayList<>(byKey.values());
        Collections.sort(sorted, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Long.compare(b.durationMs, a.durationMs);
            }
        });
        if (sorted.size() <= 5) {
            items.addAll(sorted);
            return;
        }
        items.addAll(sorted.subList(0, 4));
        Item other = new Item("other", "Other", OTHER_COLOR);
        for (int i = 4; i < sorted.size(); i++) other.durationMs += sorted.get(i).durationMs;
        items.add(other);
    }

    private void drawDonut(Canvas canvas, RectF rect) {
        float stroke = Math.max(dp(18), rect.width() * 0.16f);
        float inset = stroke / 2f + dp(4);
        RectF arc = new RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);

        paint.setColor(android.graphics.Color.WHITE);
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setColor(android.graphics.Color.rgb(230, 224, 219));
        canvas.drawCircle(rect.centerX(), rect.centerY(), (rect.width() - stroke) / 2f - dp(4), paint);

        float start = -90f;
        for (Item item : items) {
            float sweep = totalMs <= 0 ? 0 : 360f * item.durationMs / (float) totalMs;
            boolean selected = selectedKey == null || item.key.equals(selectedKey);
            paint.setColor(ColorUtils.withAlpha(item.color, selected ? 245 : 75));
            canvas.drawArc(arc, start + dp(0.2f), Math.max(0, sweep - 1.5f), false, paint);
            start += sweep;
        }
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(android.graphics.Color.WHITE);
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.28f, paint);
        drawCenter(canvas, rect);
    }

    private void drawCenter(Canvas canvas, RectF rect) {
        Item selected = selectedItem();
        long duration = selected == null ? totalMs : selected.durationMs;
        String label = selected == null ? "TOTAL" : selected.name.toUpperCase(Locale.US);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(DayflowType.sans(getContext(), true));
        paint.setTextSize(dp(8));
        paint.setColor(android.graphics.Color.rgb(165, 165, 165));
        canvas.drawText(fitText(label, rect.width() * 0.45f, dp(8), true), rect.centerX(), rect.centerY() - dp(18), paint);
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(dp(16));
        paint.setColor(android.graphics.Color.rgb(51, 51, 51));
        long minutes = Math.max(0, duration / TimeUtil.MINUTE);
        long hours = minutes / 60;
        long remaining = minutes % 60;
        canvas.drawText(hours + " " + (hours == 1 ? "hour" : "hours"), rect.centerX(), rect.centerY() + dp(3), paint);
        canvas.drawText(remaining + " " + (remaining == 1 ? "minute" : "minutes"), rect.centerX(), rect.centerY() + dp(22), paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawLegend(Canvas canvas, float x, float y, float width) {
        if (items.isEmpty()) {
            drawSans(canvas, "No activity", x, y + dp(22), dp(14), Colors.MUTED);
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            boolean selected = item.key.equals(selectedKey);
            float rowY = y + i * dp(34);
            if (selected) {
                paint.setColor(ColorUtils.withAlpha(item.color, 36));
                canvas.drawRoundRect(new RectF(x - dp(8), rowY - dp(18), getWidth() - dp(12), rowY + dp(10)), dp(6), dp(6), paint);
            }
            paint.setColor(item.color);
            canvas.drawRoundRect(new RectF(x, rowY - dp(9), x + dp(12), rowY - dp(1)), dp(2), dp(2), paint);
            drawSans(canvas, fitText(item.name, Math.max(dp(50), width - dp(54)), dp(13), false), x + dp(20), rowY, dp(13), Colors.TEXT);
            int percent = totalMs <= 0 ? 0 : Math.round(100f * item.durationMs / (float) totalMs);
            drawSans(canvas, percent + "%", getWidth() - dp(46), rowY, dp(13), Colors.TEXT);
        }
    }

    private Item selectedItem() {
        if (selectedKey == null) return null;
        for (Item item : items) if (item.key.equals(selectedKey)) return item;
        return null;
    }

    private static boolean isSystem(TimelineCard card) {
        return clean(card.category, "").toLowerCase(Locale.US).contains("system");
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String normalized(String value) {
        return clean(value, "other").toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-");
    }

    private String fitText(String text, float width, float size, boolean bold) {
        paint.setTypeface(DayflowType.sans(getContext(), bold));
        paint.setTextSize(size);
        if (paint.measureText(text) <= width) return text;
        String suffix = "...";
        int end = text.length();
        while (end > 1 && paint.measureText(text.substring(0, end) + suffix) > width) end--;
        return text.substring(0, Math.max(1, end)) + suffix;
    }

    private void drawPanel(Canvas c, float x, float y, float w, float h) {
        paint.setColor(Colors.CARD);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(16), dp(16), paint);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText(text, x, y, paint);
    }

    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText(text, x, y, paint);
    }

    private static final class Item {
        final String key;
        final String name;
        final int color;
        long durationMs;
        Item(String key, String name, int color) {
            this.key = key;
            this.name = name;
            this.color = color;
        }
    }
}
