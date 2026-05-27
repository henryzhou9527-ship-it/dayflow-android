package com.henry.dayflow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class GradientFrameLayout extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    GradientFrameLayout(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        paint.setShader(new LinearGradient(
                0, 0, 0, getHeight(),
                Colors.BACKGROUND_TOP,
                Colors.BACKGROUND_BOTTOM,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
        super.onDraw(canvas);
    }
}

final class DayflowLogoView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    DayflowLogoView(Context context) {
        super(context);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.18f;
        paint.setColor(Colors.ACCENT);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            float px = cx + (float) Math.cos(a) * r * 1.9f;
            float py = cy + (float) Math.sin(a) * r * 1.9f;
            canvas.drawCircle(px, py, r, paint);
        }
        paint.setColor(Colors.BACKGROUND_TOP);
        Path diamond = new Path();
        diamond.moveTo(cx, cy - r * 1.4f);
        diamond.lineTo(cx + r * 1.35f, cy);
        diamond.lineTo(cx, cy + r * 1.4f);
        diamond.lineTo(cx - r * 1.35f, cy);
        diamond.close();
        canvas.drawPath(diamond, paint);
    }
}

final class DashboardCanvasView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private DashboardMetrics metrics = new DashboardMetrics();

    DashboardCanvasView(Context context) {
        super(context);
        setMinimumHeight(dp(520));
    }

    void setMetrics(DashboardMetrics metrics) {
        this.metrics = metrics == null ? new DashboardMetrics() : metrics;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float gap = dp(12);
        float cardW = (w - gap) / 2f;
        drawMetricCard(canvas, 0, 0, cardW, dp(156), "APP TIME TRACKED", TimeUtil.shortDuration(metrics.trackedMs) + " tracked", null);
        drawMetricCard(canvas, cardW + gap, 0, cardW, dp(156), "FOCUS SCORE TODAY", metrics.productivePercent() + "% productive", "Distraction " + TimeUtil.shortDuration(metrics.distractionMs));
        drawCategoryChart(canvas, 0, dp(172), w, dp(170));
        drawAppBars(canvas, 0, dp(358), w, dp(150));
    }

    private void drawMetricCard(Canvas c, float x, float y, float w, float h, String label, String value, String sub) {
        drawPanel(c, x, y, w, h);
        drawSans(c, label, x + dp(18), y + dp(34), dp(13), Colors.MUTED, true);
        drawSerif(c, value, x + dp(18), y + dp(82), dp(33), Colors.TEXT);
        if (sub != null) drawSans(c, sub, x + dp(18), y + dp(112), dp(14), Colors.TEXT, false);
    }

    private void drawCategoryChart(Canvas c, float x, float y, float w, float h) {
        drawPanel(c, x, y, w, h);
        drawSans(c, "PRODUCTIVITY TRACKER", x + dp(18), y + dp(34), dp(13), Colors.MUTED, true);
        drawSerif(c, metrics.productivePercent() + "% productive today", x + dp(18), y + dp(78), dp(28), Colors.TEXT);

        float total = Math.max(1, metrics.trackedMs);
        float left = x + dp(18);
        float bottom = y + h - dp(32);
        float barW = (w - dp(72)) / Math.max(1, metrics.categoryMs.size());
        int i = 0;
        for (Map.Entry<String, Long> entry : metrics.categoryMs.entrySet()) {
            float pct = entry.getValue() / total;
            paint.setShader(new LinearGradient(0, bottom - h * pct, 0, bottom, Colors.colorForCategory(entry.getKey()), Colors.ACCENT_SOFT, Shader.TileMode.CLAMP));
            RectF r = new RectF(left + i * (barW + dp(8)), bottom - dp(90) * pct, left + i * (barW + dp(8)) + barW, bottom);
            c.drawRoundRect(r, dp(6), dp(6), paint);
            paint.setShader(null);
            drawSans(c, entry.getKey(), r.left, bottom + dp(18), dp(10), Colors.TEXT, false);
            i++;
        }
    }

    private void drawAppBars(Canvas c, float x, float y, float w, float h) {
        drawPanel(c, x, y, w, h);
        drawSans(c, "MOST USED", x + dp(18), y + dp(34), dp(13), Colors.MUTED, true);
        float top = y + dp(54);
        float max = 1;
        for (Long value : metrics.appMs.values()) max = Math.max(max, value);
        int i = 0;
        for (Map.Entry<String, Long> entry : DayflowDatabase.sortedByDuration(metrics.appMs)) {
            if (i >= 4) break;
            float yy = top + i * dp(25);
            drawSans(c, entry.getKey(), x + dp(18), yy + dp(12), dp(12), Colors.TEXT, false);
            paint.setColor(Colors.ACCENT_SOFT);
            RectF bg = new RectF(x + dp(120), yy, x + w - dp(20), yy + dp(14));
            c.drawRoundRect(bg, dp(7), dp(7), paint);
            paint.setColor(Colors.ACCENT);
            bg.right = bg.left + (w - dp(140)) * entry.getValue() / max;
            c.drawRoundRect(bg, dp(7), dp(7), paint);
            i++;
        }
    }

    private void drawPanel(Canvas c, float x, float y, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Colors.CARD);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setColor(Colors.STROKE);
        c.drawRoundRect(new RectF(x + 1, y + 1, x + w - 1, y + h - 1), dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setFakeBoldText(false);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color, boolean caps) {
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, caps ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setFakeBoldText(false);
        c.drawText(text, x, y, paint);
    }
}

final class TimelineCanvasView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<TimelineCard> cards = new ArrayList<>();
    private String day = TimeUtil.dayKey(System.currentTimeMillis());

    TimelineCanvasView(Context context) {
        super(context);
        setMinimumHeight(dp(24 * 92));
    }

    void setCards(String day, List<TimelineCard> cards) {
        this.day = day;
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long start = TimeUtil.dayStartMs(day);
        float hourH = dp(92);
        float labelW = dp(54);
        paint.setStrokeWidth(1f);
        paint.setColor(ColorUtils.withAlpha(Colors.MUTED, 60));
        for (int hour = 0; hour <= 24; hour++) {
            float y = hour * hourH;
            canvas.drawLine(labelW, y, getWidth(), y, paint);
            String label = String.format(LocaleSafe.US, "%02d:00", (hour + 4) % 24);
            drawSans(canvas, label, dp(4), y + dp(14), dp(10), Colors.MUTED);
        }

        if (cards.isEmpty()) {
            drawSerif(canvas, "No timeline yet", labelW + dp(12), dp(80), dp(28), Colors.TEXT);
            drawSans(canvas, "Start recording and Dayflow will build cards after a full 15-minute batch.", labelW + dp(12), dp(112), dp(12), Colors.MUTED);
            return;
        }

        for (TimelineCard card : cards) {
            float top = ((card.startMs - start) / (float) TimeUtil.HOUR) * hourH;
            float bottom = ((card.endMs - start) / (float) TimeUtil.HOUR) * hourH;
            top = Math.max(0, top);
            bottom = Math.max(top + dp(28), bottom);
            RectF r = new RectF(labelW + dp(6), top + dp(3), getWidth() - dp(10), bottom - dp(3));
            paint.setColor(ColorUtils.withAlpha(Colors.colorForCategory(card.category), 58));
            canvas.drawRoundRect(r, dp(12), dp(12), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f);
            paint.setColor(ColorUtils.withAlpha(Colors.colorForCategory(card.category), 160));
            canvas.drawRoundRect(r, dp(12), dp(12), paint);
            paint.setStyle(Paint.Style.FILL);
            drawSans(canvas, card.title, r.left + dp(12), r.top + dp(22), dp(14), Colors.TEXT);
            if (r.height() > dp(54)) {
                drawSans(canvas, TimeUtil.shortDuration(card.durationMs()) + " · " + card.category, r.left + dp(12), r.top + dp(42), dp(11), Colors.MUTED);
            }
        }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
}

final class DailyWorkflowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<TimelineCard> cards = new ArrayList<>();
    private String day = TimeUtil.dayKey(System.currentTimeMillis());

    DailyWorkflowView(Context context) {
        super(context);
        setMinimumHeight(dp(250));
    }

    void setCards(String day, List<TimelineCard> cards) {
        this.day = day;
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPanel(canvas, 0, 0, getWidth(), getHeight());
        drawSerif(canvas, "Your workflow so far", dp(16), dp(38), dp(24), Colors.ACCENT);
        String[] rows = {"Work", "Communication", "Personal", "Distraction", "Idle"};
        long start = TimeUtil.dayStartMs(day);
        float top = dp(62);
        float left = dp(108);
        float cell = Math.max(dp(7), (getWidth() - left - dp(20)) / 96f);
        for (int r = 0; r < rows.length; r++) {
            drawSans(canvas, rows[r], dp(12), top + r * dp(24) + dp(13), dp(10), Colors.MUTED);
            for (int i = 0; i < 96; i++) {
                paint.setColor(ColorUtils.withAlpha(Colors.MUTED, 26));
                canvas.drawRoundRect(new RectF(left + i * cell, top + r * dp(24), left + i * cell + cell - 1, top + r * dp(24) + dp(16)), 2, 2, paint);
            }
        }
        for (TimelineCard card : cards) {
            int row = rowFor(card.category);
            int s = (int) ((card.startMs - start) / (15 * TimeUtil.MINUTE));
            int e = Math.max(s + 1, (int) ((card.endMs - start) / (15 * TimeUtil.MINUTE)));
            paint.setColor(Colors.colorForCategory(card.category));
            for (int i = Math.max(0, s); i < Math.min(96, e); i++) {
                canvas.drawRoundRect(new RectF(left + i * cell, top + row * dp(24), left + i * cell + cell - 1, top + row * dp(24) + dp(16)), 2, 2, paint);
            }
        }
    }

    private int rowFor(String category) {
        if ("Communication".equals(category)) return 1;
        if ("Personal".equals(category)) return 2;
        if ("Distraction".equals(category)) return 3;
        if ("Idle".equals(category)) return 4;
        return 0;
    }

    private void drawPanel(Canvas c, float x, float y, float w, float h) {
        paint.setColor(Colors.CARD);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(18), dp(18), paint);
    }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
}

final class WeeklyCanvasView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<TimelineCard> cards = new ArrayList<>();

    WeeklyCanvasView(Context context) {
        super(context);
        setMinimumHeight(dp(520));
    }

    void setCards(List<TimelineCard> cards) {
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPanel(canvas, 0, 0, getWidth(), dp(170));
        drawSerif(canvas, "Focus and distraction heat map", dp(18), dp(38), dp(23), Colors.TEXT);
        drawHeatmap(canvas, dp(18), dp(62), getWidth() - dp(36), dp(78));
        drawPanel(canvas, 0, dp(190), getWidth(), dp(300));
        drawSerif(canvas, "Time distribution", dp(18), dp(230), dp(23), Colors.TEXT);
        drawDistribution(canvas, dp(18), dp(260), getWidth() - dp(36), dp(190));
    }

    private void drawHeatmap(Canvas c, float x, float y, float w, float h) {
        long now = System.currentTimeMillis();
        long start = TimeUtil.dayStartMs(TimeUtil.dayKey(now - 6 * TimeUtil.DAY));
        float cellW = w / 96f;
        float cellH = h / 7f;
        for (int d = 0; d < 7; d++) {
            for (int b = 0; b < 96; b++) {
                int color = ColorUtils.withAlpha(Colors.MUTED, 28);
                long blockStart = start + d * TimeUtil.DAY + b * 15 * TimeUtil.MINUTE;
                for (TimelineCard card : cards) {
                    if (card.startMs < blockStart + 15 * TimeUtil.MINUTE && card.endMs > blockStart) {
                        color = Colors.colorForCategory(card.category);
                        break;
                    }
                }
                paint.setColor(color);
                c.drawRect(x + b * cellW, y + d * cellH, x + b * cellW + cellW - 1, y + d * cellH + cellH - 1, paint);
            }
        }
    }

    private void drawDistribution(Canvas c, float x, float y, float w, float h) {
        long now = System.currentTimeMillis();
        long start = TimeUtil.dayStartMs(TimeUtil.dayKey(now - 4 * TimeUtil.DAY));
        for (int d = 0; d < 5; d++) {
            float yy = y + d * dp(34);
            drawSans(c, "Day " + (d + 1), x, yy + dp(13), dp(11), Colors.TEXT);
            float cursor = x + dp(58);
            long dayStart = start + d * TimeUtil.DAY;
            for (TimelineCard card : cards) {
                if (card.startMs >= dayStart && card.startMs < dayStart + TimeUtil.DAY) {
                    float part = Math.max(dp(8), w * card.durationMs() / (8f * TimeUtil.HOUR));
                    paint.setColor(ColorUtils.withAlpha(Colors.colorForCategory(card.category), 170));
                    c.drawRoundRect(new RectF(cursor, yy, Math.min(x + w, cursor + part), yy + dp(16)), dp(3), dp(3), paint);
                    cursor += part + dp(2);
                }
            }
        }
    }

    private void drawPanel(Canvas c, float x, float y, float w, float h) {
        paint.setColor(Colors.CARD);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(16), dp(16), paint);
    }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
}

final class ColorUtils {
    private ColorUtils() {}
    static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((alpha & 0xff) << 24);
    }
}

final class LocaleSafe {
    static final java.util.Locale US = java.util.Locale.US;
    private LocaleSafe() {}
}
