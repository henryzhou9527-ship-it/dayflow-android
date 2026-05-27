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

final class WeeklyWorkflowView extends View {
    private static final double SLOT_MINUTES = 15.0;
    private static final int EMPTY_CELL = android.graphics.Color.rgb(242, 237, 234);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<TimelineCard> cards = new ArrayList<>();
    private final Cell[][] cells = new Cell[7][];
    private final List<TotalBucket> totals = new ArrayList<>();
    private long weekStartMs = TimeUtil.weekStartMs(System.currentTimeMillis());
    private double visibleStart = 9 * 60;
    private double visibleEnd = 22 * 60;
    private int slotCount = 1;
    private int selectedDay = -1;
    private int selectedSlot = -1;
    private RectF gridRect = new RectF();

    WeeklyWorkflowView(Context context) {
        super(context);
        setMinimumHeight(dp(315));
        setWillNotDraw(false);
    }

    void setCards(long weekStartMs, List<TimelineCard> cards) {
        this.weekStartMs = weekStartMs;
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        selectedDay = -1;
        selectedSlot = -1;
        rebuild();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPanel(canvas, 0, 0, getWidth(), getHeight());
        drawSerif(canvas, "Your workflow this week", dp(82), dp(34), dp(20), Colors.ACCENT);
        if (slotCount <= 0) {
            drawEmpty(canvas);
            return;
        }

        float labelWidth = dp(34);
        float left = dp(34);
        float top = dp(58);
        float gridLeft = left + labelWidth + dp(12);
        float gridTop = top;
        float gridWidth = getWidth() - gridLeft - dp(18);
        float cellGap = dp(2);
        float rowHeight = dp(14);
        float cellW = Math.max(dp(2), (gridWidth - (slotCount - 1) * cellGap) / Math.max(1, slotCount));
        gridRect.set(gridLeft, gridTop, gridLeft + slotCount * cellW + (slotCount - 1) * cellGap, gridTop + 7 * rowHeight + 6 * cellGap);

        drawRows(canvas, left, labelWidth, gridLeft, gridTop, cellW, rowHeight, cellGap);
        drawAxis(canvas, gridLeft, gridTop + 7 * rowHeight + 6 * cellGap + dp(15), cellW, cellGap);
        drawFooter(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        if (!gridRect.contains(event.getX(), event.getY())) {
            selectedDay = -1;
            selectedSlot = -1;
            invalidate();
            return true;
        }
        float rowPitch = gridRect.height() / 7f;
        selectedDay = Math.max(0, Math.min(6, (int) ((event.getY() - gridRect.top) / rowPitch)));
        selectedSlot = Math.max(0, Math.min(slotCount - 1, Math.round((event.getX() - gridRect.left) / (gridRect.width() / Math.max(1, slotCount)))));
        invalidate();
        return true;
    }

    private void rebuild() {
        List<TimelineCard> visible = visibleCards();
        computeWindow(visible);
        slotCount = Math.max(1, (int) Math.ceil((visibleEnd - visibleStart) / SLOT_MINUTES));
        for (int day = 0; day < 7; day++) {
            cells[day] = new Cell[slotCount];
            List<TimelineCard> dayCards = cardsForDay(visible, day);
            for (int slot = 0; slot < slotCount; slot++) {
                cells[day][slot] = buildCell(dayCards, slot);
            }
        }
        buildTotals(visible);
    }

    private List<TimelineCard> visibleCards() {
        List<TimelineCard> visible = new ArrayList<>();
        for (TimelineCard card : cards) {
            if (ignored(card) || card.durationMs() <= 0) continue;
            visible.add(card);
        }
        Collections.sort(visible, new Comparator<TimelineCard>() {
            @Override public int compare(TimelineCard a, TimelineCard b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        return visible;
    }

    private void computeWindow(List<TimelineCard> visible) {
        if (visible.isEmpty()) {
            visibleStart = 9 * 60;
            visibleEnd = 22 * 60;
            return;
        }
        double earliest = 28 * 60;
        double latest = 4 * 60;
        for (TimelineCard card : visible) {
            int day = dayIndex(card);
            if (day < 0 || day > 6) continue;
            earliest = Math.min(earliest, minuteOfWorkflowDay(card.startMs, day));
            latest = Math.max(latest, minuteOfWorkflowDay(card.endMs, day));
        }
        double paddedStart = Math.max(4 * 60, earliest - 30);
        double paddedEnd = Math.min(28 * 60, latest + 30);
        visibleStart = Math.floor(paddedStart / SLOT_MINUTES) * SLOT_MINUTES;
        visibleEnd = Math.ceil(paddedEnd / SLOT_MINUTES) * SLOT_MINUTES;
        if (visibleEnd == 24 * 60) visibleEnd = 28 * 60;
        if (visibleEnd <= visibleStart) {
            visibleStart = 9 * 60;
            visibleEnd = 22 * 60;
        }
    }

    private List<TimelineCard> cardsForDay(List<TimelineCard> visible, int day) {
        List<TimelineCard> result = new ArrayList<>();
        for (TimelineCard card : visible) {
            if (dayIndex(card) == day) result.add(card);
        }
        return result;
    }

    private Cell buildCell(List<TimelineCard> dayCards, int slot) {
        double slotStart = visibleStart + slot * SLOT_MINUTES;
        double slotEnd = slotStart + SLOT_MINUTES;
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        double total = 0;
        for (TimelineCard card : dayCards) {
            int day = dayIndex(card);
            if (day < 0) continue;
            double start = minuteOfWorkflowDay(card.startMs, day);
            double end = minuteOfWorkflowDay(card.endMs, day);
            double overlap = Math.max(0, Math.min(end, slotEnd) - Math.max(start, slotStart));
            if (overlap <= 0) continue;
            Bucket bucket = bucketFor(card);
            Bucket current = buckets.get(bucket.key);
            if (current == null) {
                current = bucket;
                buckets.put(bucket.key, current);
            }
            current.minutes += overlap;
            total += overlap;
        }
        if (buckets.isEmpty()) return new Cell(null, null, EMPTY_CELL, 0, 0);
        Bucket dominant = null;
        for (Bucket bucket : buckets.values()) {
            if (dominant == null || bucket.minutes > dominant.minutes) dominant = bucket;
        }
        int color = dominant == null ? EMPTY_CELL : dominant.color;
        return new Cell(dominant == null ? null : dominant.key, dominant == null ? null : dominant.name, color, total, Math.min(1, total / SLOT_MINUTES));
    }

    private void buildTotals(List<TimelineCard> visible) {
        totals.clear();
        Map<String, TotalBucket> byKey = new LinkedHashMap<>();
        for (TimelineCard card : visible) {
            Bucket bucket = bucketFor(card);
            TotalBucket total = byKey.get(bucket.key);
            if (total == null) {
                total = new TotalBucket(bucket.key, bucket.name, bucket.color);
                byKey.put(bucket.key, total);
            }
            total.minutes += Math.max(1, card.durationMs() / (double) TimeUtil.MINUTE);
        }
        totals.addAll(byKey.values());
        Collections.sort(totals, new Comparator<TotalBucket>() {
            @Override public int compare(TotalBucket a, TotalBucket b) {
                return Double.compare(b.minutes, a.minutes);
            }
        });
        while (totals.size() > 6) totals.remove(totals.size() - 1);
    }

    private void drawRows(Canvas canvas, float left, float labelWidth, float gridLeft, float gridTop, float cellW, float rowHeight, float gap) {
        String[] labels = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int day = 0; day < 7; day++) {
            float y = gridTop + day * (rowHeight + gap);
            drawSans(canvas, labels[day], left + labelWidth - dp(28), y + dp(11), dp(11), Colors.TEXT);
            for (int slot = 0; slot < slotCount; slot++) {
                float x = gridLeft + slot * (cellW + gap);
                Cell cell = cells[day][slot];
                boolean selected = day == selectedDay && slot == selectedSlot;
                int alpha = cell == null || cell.occupancy <= 0 ? 255 : (int) (80 + 175 * cell.occupancy);
                paint.setColor(cell == null || cell.categoryName == null ? EMPTY_CELL : ColorUtils.withAlpha(cell.color, alpha));
                canvas.drawRoundRect(new RectF(x, y, x + cellW, y + rowHeight), dp(2.5f), dp(2.5f), paint);
                if (selected) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    paint.setColor(Colors.TEXT);
                    canvas.drawRoundRect(new RectF(x, y, x + cellW, y + rowHeight), dp(3), dp(3), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        }
    }

    private void drawAxis(Canvas canvas, float gridLeft, float y, float cellW, float gap) {
        paint.setColor(android.graphics.Color.rgb(224, 217, 213));
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(gridLeft, y - dp(7), gridLeft + slotCount * (cellW + gap) - gap, y - dp(7), paint);
        int firstHour = (int) Math.ceil(visibleStart / 60.0);
        int lastHour = (int) Math.floor(visibleEnd / 60.0);
        for (int hour = firstHour; hour <= lastHour; hour++) {
            int minute = hour * 60;
            float progress = (float) ((minute - visibleStart) / Math.max(SLOT_MINUTES, visibleEnd - visibleStart));
            float x = gridLeft + progress * (slotCount * (cellW + gap) - gap);
            drawSans(canvas, clockLabel(minute), Math.min(getWidth() - dp(42), Math.max(gridLeft, x - dp(14))), y + dp(10), dp(9), Colors.MUTED);
        }
    }

    private void drawFooter(Canvas canvas) {
        RectF footer = new RectF(0, getHeight() - dp(72), getWidth(), getHeight());
        paint.setColor(ColorUtils.withAlpha(Colors.CARD_ALT, 235));
        canvas.drawRoundRect(footer, dp(16), dp(16), paint);
        paint.setColor(Colors.STROKE);
        canvas.drawLine(dp(12), footer.top, getWidth() - dp(12), footer.top, paint);
        if (selectedDay >= 0 && selectedSlot >= 0) {
            drawSelectedCell(canvas, footer);
            return;
        }
        drawSerif(canvas, "Week total", dp(16), footer.top + dp(30), dp(14), Colors.MUTED);
        if (totals.isEmpty()) {
            drawSans(canvas, "No captured activity during " + clockLabel((int) visibleStart) + "-" + clockLabel((int) visibleEnd), dp(92), footer.top + dp(29), dp(12), Colors.MUTED);
            return;
        }
        float x = dp(92);
        for (TotalBucket total : totals) {
            String text = total.name + " " + TimeUtil.shortDuration((long) (total.minutes * TimeUtil.MINUTE));
            paint.setColor(ColorUtils.withAlpha(total.color, 34));
            RectF pill = new RectF(x, footer.top + dp(14), Math.min(getWidth() - dp(12), x + textWidth(text, dp(12), false) + dp(16)), footer.top + dp(38));
            canvas.drawRoundRect(pill, dp(5), dp(5), paint);
            drawSans(canvas, fitText(text, pill.width() - dp(10), dp(12), false), pill.left + dp(8), pill.top + dp(16), dp(12), total.color);
            x = pill.right + dp(7);
            if (x > getWidth() - dp(80)) break;
        }
    }

    private void drawSelectedCell(Canvas canvas, RectF footer) {
        Cell cell = cells[selectedDay][selectedSlot];
        String day = new String[]{"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"}[selectedDay];
        int startMinute = (int) Math.round(visibleStart + selectedSlot * SLOT_MINUTES);
        int endMinute = (int) Math.round(Math.min(visibleEnd, startMinute + SLOT_MINUTES));
        String label = day + " " + clockLabel(startMinute) + "-" + clockLabel(endMinute);
        String detail = cell == null || cell.categoryName == null
                ? "No activity"
                : cell.categoryName + " · " + TimeUtil.shortDuration((long) (cell.minutes * TimeUtil.MINUTE));
        drawSerif(canvas, fitText(label, getWidth() - dp(34), dp(14), false), dp(16), footer.top + dp(28), dp(14), Colors.TEXT);
        drawSans(canvas, fitText(detail, getWidth() - dp(34), dp(12), false), dp(16), footer.top + dp(49), dp(12), Colors.MUTED);
    }

    private void drawEmpty(Canvas canvas) {
        RectF box = new RectF(dp(18), dp(74), getWidth() - dp(18), dp(150));
        paint.setColor(Colors.CARD_ALT);
        canvas.drawRoundRect(box, dp(12), dp(12), paint);
        drawSans(canvas, "Workflow fills in after analyzed timeline cards exist for this week.", box.left + dp(12), box.top + dp(38), dp(13), Colors.MUTED);
    }

    private int dayIndex(TimelineCard card) {
        return (int) ((card.startMs - weekStartMs) / TimeUtil.DAY);
    }

    private double minuteOfWorkflowDay(long timestampMs, int day) {
        long dayStart = weekStartMs + day * TimeUtil.DAY;
        return 4 * 60 + (timestampMs - dayStart) / (double) TimeUtil.MINUTE;
    }

    private static Bucket bucketFor(TimelineCard card) {
        String category = clean(card.category, "Work");
        if (category.toLowerCase(Locale.US).contains("distraction")) {
            return new Bucket("distraction", "Distraction", Colors.DISTRACTION);
        }
        String key = category.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-");
        return new Bucket(key, category, Colors.colorForCategory(category));
    }

    private static boolean ignored(TimelineCard card) {
        String category = clean(card.category, "").toLowerCase(Locale.US);
        return category.contains("idle") || category.contains("system");
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String clockLabel(int minute) {
        int normalized = ((minute % 1440) + 1440) % 1440;
        int hour24 = normalized / 60;
        int minutePart = normalized % 60;
        int hour12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
        String suffix = hour24 < 12 ? "am" : "pm";
        if (minutePart > 0) return String.format(Locale.US, "%d:%02d%s", hour12, minutePart, suffix);
        return hour12 + suffix;
    }

    private float textWidth(String text, float size, boolean bold) {
        paint.setTypeface(DayflowType.sans(getContext(), bold));
        paint.setTextSize(size);
        return paint.measureText(text);
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

    private static final class Bucket {
        final String key;
        final String name;
        final int color;
        double minutes;
        Bucket(String key, String name, int color) {
            this.key = key;
            this.name = name;
            this.color = color;
        }
    }

    private static final class Cell {
        final String categoryKey;
        final String categoryName;
        final int color;
        final double minutes;
        final double occupancy;
        Cell(String categoryKey, String categoryName, int color, double minutes, double occupancy) {
            this.categoryKey = categoryKey;
            this.categoryName = categoryName;
            this.color = color;
            this.minutes = minutes;
            this.occupancy = occupancy;
        }
    }

    private static final class TotalBucket {
        final String key;
        final String name;
        final int color;
        double minutes;
        TotalBucket(String key, String name, int color) {
            this.key = key;
            this.name = name;
            this.color = color;
        }
    }
}
