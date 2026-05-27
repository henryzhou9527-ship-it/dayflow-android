package com.henry.dayflow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class WeeklyContextChartsView extends View {
    private static final int DISTRACTION_COLOR = android.graphics.Color.rgb(255, 138, 138);
    private static final int SHIFT_COLOR = android.graphics.Color.rgb(167, 140, 255);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DayStats[] days = new DayStats[7];
    private List<TimelineCard> cards = new ArrayList<>();
    private long weekStartMs = TimeUtil.weekStartMs(System.currentTimeMillis());
    private int selectedDayIndex = -1;
    private RectF chartRect = new RectF();

    WeeklyContextChartsView(Context context) {
        super(context);
        for (int i = 0; i < days.length; i++) {
            days[i] = new DayStats(i, labelForDay(i));
        }
        setMinimumHeight(dp(330));
        setWillNotDraw(false);
    }

    void setCards(long weekStartMs, List<TimelineCard> cards) {
        this.weekStartMs = weekStartMs;
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        selectedDayIndex = -1;
        rebuild();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPanel(canvas, 0, 0, getWidth(), getHeight());
        drawSerif(canvas, fitText("Context shift and distractions comparison", getWidth() - dp(36), dp(21), false), dp(18), dp(38), dp(21), Colors.ACCENT);
        drawLegend(canvas, dp(18), dp(66));

        chartRect = new RectF(dp(42), dp(104), getWidth() - dp(20), dp(218));
        drawAxes(canvas);
        drawLine(canvas, true);
        drawLine(canvas, false);
        drawPoints(canvas);
        drawDayLabels(canvas);
        drawFooter(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        if (!chartRect.contains(event.getX(), event.getY())) {
            selectedDayIndex = -1;
            invalidate();
            return true;
        }
        float step = chartRect.width() / 6f;
        selectedDayIndex = Math.max(0, Math.min(6, Math.round((event.getX() - chartRect.left) / step)));
        invalidate();
        return true;
    }

    private void rebuild() {
        for (int i = 0; i < days.length; i++) {
            days[i].distractions = 0;
            days[i].shifts = 0;
            days[i].events = 0;
        }
        List<TimelineCard> sorted = new ArrayList<>(cards);
        Collections.sort(sorted, new Comparator<TimelineCard>() {
            @Override public int compare(TimelineCard a, TimelineCard b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        String[] previousCategory = new String[7];
        for (TimelineCard card : sorted) {
            if (ignored(card)) continue;
            int dayIndex = (int) ((card.startMs - weekStartMs) / TimeUtil.DAY);
            if (dayIndex < 0 || dayIndex >= days.length) continue;
            String category = normalizedCategory(card);
            if (previousCategory[dayIndex] != null && !previousCategory[dayIndex].equals(category)) {
                days[dayIndex].shifts++;
                if (isDistributionHour(card.startMs)) days[dayIndex].events++;
            }
            previousCategory[dayIndex] = category;
            if (isDistraction(card)) {
                days[dayIndex].distractions++;
                if (isDistributionHour(card.startMs)) days[dayIndex].events++;
            }
        }
    }

    private void drawLegend(Canvas canvas, float x, float y) {
        legend(canvas, "Number of times distracted", DISTRACTION_COLOR, x, y);
        float secondX = getWidth() < dp(390) ? x : x + dp(176);
        float secondY = getWidth() < dp(390) ? y + dp(18) : y;
        legend(canvas, "Number of context shifts", SHIFT_COLOR, secondX, secondY);
    }

    private void legend(Canvas canvas, String label, int color, float x, float y) {
        paint.setColor(color);
        canvas.drawCircle(x + dp(5), y - dp(4), dp(5), paint);
        drawSans(canvas, label, x + dp(16), y, dp(11), Colors.TEXT);
    }

    private void drawAxes(Canvas canvas) {
        paint.setColor(ColorUtils.withAlpha(Colors.MUTED, 150));
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(chartRect.left, chartRect.top, chartRect.left, chartRect.bottom, paint);
        canvas.drawLine(chartRect.left, chartRect.bottom, chartRect.right, chartRect.bottom, paint);
        drawSans(canvas, "Count", chartRect.left - dp(34), chartRect.top - dp(8), dp(11), Colors.TEXT);

        int max = yMax();
        for (int i = 1; i <= 3; i++) {
            float y = chartRect.bottom - chartRect.height() * i / 3f;
            paint.setColor(ColorUtils.withAlpha(Colors.STROKE, 110));
            canvas.drawLine(chartRect.left, y, chartRect.right, y, paint);
            drawSans(canvas, String.valueOf(Math.round(max * i / 3f)), chartRect.left - dp(30), y + dp(4), dp(9), Colors.MUTED);
        }
    }

    private void drawLine(Canvas canvas, boolean distractions) {
        Path path = new Path();
        for (int i = 0; i < days.length; i++) {
            float x = xForDay(i);
            float y = yForValue(distractions ? days[i].distractions : days[i].shifts);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(distractions ? DISTRACTION_COLOR : SHIFT_COLOR);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawPoints(Canvas canvas) {
        if (selectedDayIndex >= 0) {
            paint.setColor(ColorUtils.withAlpha(Colors.ACCENT, 45));
            canvas.drawRoundRect(
                    new RectF(xForDay(selectedDayIndex) - dp(18), chartRect.top, xForDay(selectedDayIndex) + dp(18), chartRect.bottom),
                    dp(8),
                    dp(8),
                    paint);
        }
        for (int i = 0; i < days.length; i++) {
            boolean selected = selectedDayIndex == i;
            drawPoint(canvas, xForDay(i), yForValue(days[i].distractions), DISTRACTION_COLOR, selected);
            drawPoint(canvas, xForDay(i), yForValue(days[i].shifts), SHIFT_COLOR, selected);
        }
    }

    private void drawPoint(Canvas canvas, float x, float y, int color, boolean selected) {
        paint.setColor(Colors.CARD);
        canvas.drawCircle(x, y, selected ? dp(6) : dp(4), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(selected ? dp(3) : dp(2));
        paint.setColor(color);
        canvas.drawCircle(x, y, selected ? dp(6) : dp(4), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawDayLabels(Canvas canvas) {
        for (int i = 0; i < days.length; i++) {
            int color = selectedDayIndex == i ? Colors.TEXT : Colors.MUTED;
            drawSans(canvas, days[i].label, xForDay(i) - dp(10), chartRect.bottom + dp(22), dp(11), color);
        }
    }

    private void drawFooter(Canvas canvas) {
        RectF footer = new RectF(0, getHeight() - dp(78), getWidth(), getHeight());
        paint.setColor(ColorUtils.withAlpha(Colors.CARD_ALT, 235));
        canvas.drawRoundRect(footer, dp(16), dp(16), paint);
        paint.setColor(Colors.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(dp(12), footer.top, getWidth() - dp(12), footer.top, paint);
        paint.setColor(android.graphics.Color.rgb(245, 173, 65));
        canvas.drawCircle(dp(24), footer.top + dp(28), dp(5), paint);

        String line = selectedDayIndex >= 0 ? selectedDayLine(days[selectedDayIndex]) : insightLine();
        String[] pieces = wrap(line, getWidth() - dp(58), dp(13));
        drawSans(canvas, pieces[0], dp(40), footer.top + dp(26), dp(13), Colors.TEXT);
        if (pieces.length > 1) {
            drawSans(canvas, pieces[1], dp(40), footer.top + dp(46), dp(12), Colors.MUTED);
        }
    }

    private String insightLine() {
        DayStats busiest = null;
        for (DayStats day : days) {
            if (busiest == null || day.interruptions() > busiest.interruptions()) busiest = day;
        }
        if (busiest == null || busiest.interruptions() == 0) {
            return "No context shift or distraction pattern was detected in this week.";
        }
        return busiest.label + " had the most interruptions, with " + busiest.shifts
                + " context shifts and " + busiest.distractions + " distractions.";
    }

    private String selectedDayLine(DayStats day) {
        return day.label + ": " + day.shifts + " context shifts, " + day.distractions
                + " distractions, " + day.events + " visible 10:00-18:00 events.";
    }

    private int yMax() {
        int max = 4;
        for (DayStats day : days) max = Math.max(max, Math.max(day.distractions, day.shifts) + 2);
        return max;
    }

    private float xForDay(int index) {
        return chartRect.left + chartRect.width() * index / 6f;
    }

    private float yForValue(int value) {
        return chartRect.bottom - chartRect.height() * value / (float) yMax();
    }

    private static boolean ignored(TimelineCard card) {
        String category = normalizedCategory(card);
        return category.contains("idle") || category.contains("system");
    }

    private static boolean isDistraction(TimelineCard card) {
        return normalizedCategory(card).contains("distraction");
    }

    private static String normalizedCategory(TimelineCard card) {
        return (card.category == null ? "" : card.category).toLowerCase(Locale.US);
    }

    private static boolean isDistributionHour(long timestampMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(timestampMs);
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 10 && hour <= 18;
    }

    private static String labelForDay(int index) {
        String[] labels = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        return labels[Math.max(0, Math.min(labels.length - 1, index))];
    }

    private String[] wrap(String text, float width, float size) {
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(size);
        if (paint.measureText(text) <= width) return new String[]{text};
        int split = Math.min(text.length(), 1);
        for (int i = 1; i < text.length(); i++) {
            if (paint.measureText(text.substring(0, i)) > width) break;
            split = i;
        }
        int space = text.lastIndexOf(' ', split);
        if (space > 0) split = space;
        return new String[]{text.substring(0, split).trim(), text.substring(split).trim()};
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

    private static final class DayStats {
        final int index;
        final String label;
        int distractions;
        int shifts;
        int events;
        DayStats(int index, String label) {
            this.index = index;
            this.label = label;
        }
        int interruptions() {
            return distractions + shifts;
        }
    }
}
