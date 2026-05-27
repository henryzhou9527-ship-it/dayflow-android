package com.henry.dayflow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WeeklyOverviewFooterView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<TimelineCard> cards = new ArrayList<>();
    private long weekStartMs = TimeUtil.weekStartMs(System.currentTimeMillis());
    private int contextSwitchTotal;
    private int contextSwitchAverage;
    private long totalFocusMs;
    private long longestFocusMs;
    private String longestFocusDay = "No focus yet";
    private String primaryFocusName = "No focus yet";
    private long primaryFocusMs;

    WeeklyOverviewFooterView(Context context) {
        super(context);
        setMinimumHeight(dp(158));
        setWillNotDraw(false);
    }

    void setCards(long weekStartMs, List<TimelineCard> cards) {
        this.weekStartMs = weekStartMs;
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        rebuild();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPanel(canvas, 0, 0, getWidth(), getHeight());
        if (getWidth() >= dp(560)) {
            RectF left = new RectF(0, 0, getWidth() * 0.32f, getHeight());
            RectF right = new RectF(left.right, 0, getWidth(), getHeight());
            drawDivider(canvas, left.right);
            drawContextGroup(canvas, left);
            drawFocusGroup(canvas, right);
        } else {
            RectF top = new RectF(0, 0, getWidth(), getHeight() * 0.46f);
            RectF bottom = new RectF(0, top.bottom, getWidth(), getHeight());
            drawHorizontalDivider(canvas, top.bottom);
            drawContextGroup(canvas, top);
            drawFocusGroup(canvas, bottom);
        }
    }

    private void rebuild() {
        contextSwitchTotal = 0;
        totalFocusMs = 0;
        longestFocusMs = 0;
        longestFocusDay = "No focus yet";
        primaryFocusName = "No focus yet";
        primaryFocusMs = 0;

        Map<String, Long> focusByCategory = new LinkedHashMap<>();
        for (int day = 0; day < 7; day++) {
            List<TimelineCard> dayCards = cardsForDay(day);
            contextSwitchTotal += contextSwitches(dayCards);
            FocusRange longest = longestFocusForDay(dayCards);
            if (longest != null && longest.durationMs() > longestFocusMs) {
                longestFocusMs = longest.durationMs();
                longestFocusDay = fullDayLabel(day);
            }
        }
        contextSwitchAverage = Math.round(contextSwitchTotal / 7f);

        for (TimelineCard card : cards) {
            if (!isFocusCandidate(card)) continue;
            long duration = Math.max(0, card.durationMs());
            totalFocusMs += duration;
            String category = clean(card.category, "Uncategorized");
            Long current = focusByCategory.get(category);
            focusByCategory.put(category, current == null ? duration : current + duration);
        }
        for (Map.Entry<String, Long> entry : focusByCategory.entrySet()) {
            if (entry.getValue() > primaryFocusMs) {
                primaryFocusName = entry.getKey();
                primaryFocusMs = entry.getValue();
            }
        }
    }

    private List<TimelineCard> cardsForDay(int day) {
        List<TimelineCard> result = new ArrayList<>();
        long start = weekStartMs + day * TimeUtil.DAY;
        long end = start + TimeUtil.DAY;
        for (TimelineCard card : cards) {
            if (card.startMs >= start && card.startMs < end) result.add(card);
        }
        Collections.sort(result, new Comparator<TimelineCard>() {
            @Override public int compare(TimelineCard a, TimelineCard b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        return result;
    }

    private int contextSwitches(List<TimelineCard> dayCards) {
        int switches = 0;
        String previous = null;
        for (TimelineCard card : dayCards) {
            if (isSystem(card)) continue;
            String current = normalizedCategory(card);
            if (previous != null && !previous.equals(current)) switches++;
            previous = current;
        }
        return switches;
    }

    private FocusRange longestFocusForDay(List<TimelineCard> dayCards) {
        List<FocusRange> ranges = new ArrayList<>();
        for (TimelineCard card : dayCards) {
            if (!isFocusCandidate(card)) continue;
            ranges.add(new FocusRange(card.startMs, card.endMs));
        }
        if (ranges.isEmpty()) return null;
        Collections.sort(ranges, new Comparator<FocusRange>() {
            @Override public int compare(FocusRange a, FocusRange b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        List<FocusRange> merged = new ArrayList<>();
        FocusRange current = ranges.get(0);
        for (int i = 1; i < ranges.size(); i++) {
            FocusRange next = ranges.get(i);
            long gap = next.startMs - current.endMs;
            if (gap < 5 * TimeUtil.MINUTE) {
                current.endMs = Math.max(current.endMs, next.endMs);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        FocusRange best = null;
        for (FocusRange range : merged) {
            if (best == null || range.durationMs() > best.durationMs()) best = range;
        }
        return best;
    }

    private void drawContextGroup(Canvas canvas, RectF rect) {
        drawSerif(canvas, "Context switch", rect.left + dp(18), rect.top + dp(30), dp(16), Colors.ACCENT);
        drawMetric(canvas, "Total", contextSwitchTotal + " times", rect.left + dp(18), rect.top + dp(58), rect.width() * 0.42f);
        drawMetric(canvas, "Average", contextSwitchAverage + " times / day", rect.left + rect.width() * 0.48f, rect.top + dp(58), rect.width() * 0.48f);
    }

    private void drawFocusGroup(Canvas canvas, RectF rect) {
        drawSerif(canvas, "Focus", rect.left + dp(18), rect.top + dp(30), dp(16), Colors.ACCENT);
        float available = rect.width() - dp(36);
        if (rect.width() > dp(430)) {
            float col = available / 3f;
            drawMetric(canvas, "Total length", TimeUtil.shortDuration(totalFocusMs), rect.left + dp(18), rect.top + dp(58), col - dp(8));
            drawMetric(canvas, "Longest duration", longestFocusText(), rect.left + dp(18) + col, rect.top + dp(58), col - dp(8));
            drawMetric(canvas, "Primary focus", primaryFocusText(), rect.left + dp(18) + col * 2, rect.top + dp(58), col - dp(8));
        } else {
            float col = available / 2f;
            drawMetric(canvas, "Total length", TimeUtil.shortDuration(totalFocusMs), rect.left + dp(18), rect.top + dp(58), col - dp(8));
            drawMetric(canvas, "Longest", longestFocusText(), rect.left + dp(18) + col, rect.top + dp(58), col - dp(8));
            drawMetric(canvas, "Primary focus", primaryFocusText(), rect.left + dp(18), rect.top + dp(98), available);
        }
    }

    private void drawMetric(Canvas canvas, String label, String value, float x, float y, float width) {
        drawSans(canvas, fitText(label, width, dp(11), false), x, y, dp(11), Colors.MUTED);
        drawSerif(canvas, fitText(value, width, dp(18), false), x, y + dp(24), dp(18), Colors.TEXT);
    }

    private String longestFocusText() {
        if (longestFocusMs <= 0) return "No focus yet";
        return TimeUtil.shortDuration(longestFocusMs) + ", " + longestFocusDay;
    }

    private String primaryFocusText() {
        if (primaryFocusMs <= 0) return "No focus yet";
        return primaryFocusName + ", " + TimeUtil.shortDuration(primaryFocusMs);
    }

    private static boolean isFocusCandidate(TimelineCard card) {
        return !isSystem(card) && !isIdle(card);
    }

    private static boolean isSystem(TimelineCard card) {
        return normalizedCategory(card).contains("system");
    }

    private static boolean isIdle(TimelineCard card) {
        return normalizedCategory(card).contains("idle");
    }

    private static String normalizedCategory(TimelineCard card) {
        return clean(card.category, "").toLowerCase(Locale.US);
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String fullDayLabel(int day) {
        String[] labels = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
        return labels[Math.max(0, Math.min(labels.length - 1, day))];
    }

    private void drawPanel(Canvas c, float x, float y, float w, float h) {
        paint.setColor(Colors.CARD_ALT);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(16), dp(16), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Colors.STROKE);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(16), dp(16), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawDivider(Canvas canvas, float x) {
        paint.setColor(Colors.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(x, dp(12), x, getHeight() - dp(12), paint);
    }

    private void drawHorizontalDivider(Canvas canvas, float y) {
        paint.setColor(Colors.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(dp(12), y, getWidth() - dp(12), y, paint);
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

    private static final class FocusRange {
        long startMs;
        long endMs;
        FocusRange(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
        long durationMs() {
            return Math.max(0, endMs - startMs);
        }
    }
}
