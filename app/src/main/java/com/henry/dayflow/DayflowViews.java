package com.henry.dayflow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private Bitmap logo;

    DayflowLogoView(Context context) {
        super(context);
        try {
            InputStream in = context.getAssets().open("images/dayflow_logo_main.png");
            logo = BitmapFactory.decodeStream(in);
            in.close();
        } catch (Exception ignored) {
            logo = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (logo != null) {
            paint.setFilterBitmap(true);
            canvas.drawBitmap(logo, null, new RectF(0, 0, getWidth(), getHeight()), paint);
            return;
        }
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

final class TimelineReviewScrubberView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            float duration = Math.max(1f, cardDurationMs());
            progress += 700f / duration;
            if (progress >= 1f) progress = 0f;
            updateCurrentBitmap();
            invalidate();
            handler.postDelayed(this, 700);
        }
    };
    private List<ScreenshotRecord> frames = new ArrayList<>();
    private TimelineCard card;
    private Bitmap currentBitmap;
    private int currentIndex = -1;
    private float progress;
    private boolean playing;
    private float downX;
    private float downY;
    private boolean scrubbing;

    TimelineReviewScrubberView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    void setData(TimelineCard card, List<ScreenshotRecord> frames) {
        this.card = card;
        this.frames = frames == null ? new ArrayList<ScreenshotRecord>() : frames;
        progress = 0f;
        currentIndex = -1;
        recycleCurrent();
        updateCurrentBitmap();
        invalidate();
    }

    void stopPlayback() {
        playing = false;
        handler.removeCallbacks(tick);
    }

    @Override protected void onDetachedFromWindow() {
        stopPlayback();
        recycleCurrent();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        drawMedia(canvas, w, h);
        drawOverlay(canvas, w, h);
        drawScrubber(canvas, w, h);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (frames.isEmpty()) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                scrubbing = true;
                stopPlayback();
                updateProgressFromX(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateProgressFromX(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                updateProgressFromX(event.getX());
                boolean tap = Math.abs(event.getX() - downX) < dp(8) && Math.abs(event.getY() - downY) < dp(8);
                scrubbing = false;
                if (tap) togglePlayback();
                return true;
            default:
                return true;
        }
    }

    private void togglePlayback() {
        playing = !playing;
        handler.removeCallbacks(tick);
        if (playing) handler.post(tick);
        invalidate();
    }

    private void updateProgressFromX(float x) {
        float left = dp(18);
        float right = Math.max(left + 1, getWidth() - dp(18));
        progress = Math.max(0f, Math.min(1f, (x - left) / (right - left)));
        updateCurrentBitmap();
        invalidate();
    }

    private void updateCurrentBitmap() {
        int index = frameIndexForProgress();
        if (index == currentIndex) return;
        currentIndex = index;
        recycleCurrent();
        if (index < 0 || index >= frames.size()) return;
        currentBitmap = decodePreview(frames.get(index).filePath);
    }

    private int frameIndexForProgress() {
        if (frames.isEmpty()) return -1;
        if (card == null || card.endMs <= card.startMs) {
            return Math.min(frames.size() - 1, Math.round(progress * (frames.size() - 1)));
        }
        long target = card.startMs + Math.round(cardDurationMs() * progress);
        int bestIndex = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < frames.size(); i++) {
            long distance = Math.abs(frames.get(i).capturedAtMs - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private Bitmap decodePreview(String path) {
        if (path == null || !new File(path).isFile()) return null;
        try {
            return ScreenshotStorage.decodeBitmap(path, 900);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void drawMedia(Canvas canvas, float w, float h) {
        RectF frame = new RectF(0, 0, w, h);
        if (currentBitmap != null) {
            paint.setShader(null);
            paint.setFilterBitmap(true);
            RectF dst = centerCropRect(currentBitmap.getWidth(), currentBitmap.getHeight(), frame);
            canvas.drawBitmap(currentBitmap, null, dst, paint);
            paint.setFilterBitmap(false);
        } else {
            paint.setShader(new LinearGradient(0, 0, w, h, 0xff2f2a24, 0xfff5e8d8, Shader.TileMode.CLAMP));
            canvas.drawRect(frame, paint);
            paint.setShader(null);
        }
        paint.setColor(0x44000000);
        canvas.drawRect(frame, paint);
    }

    private void drawOverlay(Canvas canvas, float w, float h) {
        paint.setShader(new LinearGradient(0, 0, 0, h, 0x99000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, dp(88), paint);
        paint.setShader(null);

        String title = card == null || card.title == null ? "Timeline review" : card.title;
        drawSerif(canvas, title, dp(18), dp(34), dp(22), 0xffffffff);
        String detail = currentFrameLabel();
        drawSans(canvas, detail, dp(18), dp(58), dp(11), 0xeeffffff, false);

        float cx = w - dp(38);
        float cy = dp(38);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x88000000);
        canvas.drawCircle(cx, cy, dp(22), paint);
        paint.setColor(0xffffffff);
        Path icon = new Path();
        if (playing) {
            float barW = dp(4);
            canvas.drawRoundRect(new RectF(cx - dp(8), cy - dp(9), cx - dp(8) + barW, cy + dp(9)), dp(2), dp(2), paint);
            canvas.drawRoundRect(new RectF(cx + dp(4), cy - dp(9), cx + dp(4) + barW, cy + dp(9)), dp(2), dp(2), paint);
        } else {
            icon.moveTo(cx - dp(6), cy - dp(10));
            icon.lineTo(cx + dp(10), cy);
            icon.lineTo(cx - dp(6), cy + dp(10));
            icon.close();
            canvas.drawPath(icon, paint);
        }
    }

    private void drawScrubber(Canvas canvas, float w, float h) {
        float left = dp(18);
        float right = w - dp(18);
        float lineY = h - dp(18);
        paint.setStrokeWidth(dp(4));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0x88a3978d);
        canvas.drawLine(left, lineY, right, lineY, paint);
        paint.setColor(0xccf96e00);
        float x = left + (right - left) * Math.max(0f, Math.min(1f, progress));
        canvas.drawLine(left, lineY, x, lineY, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        String time = displayTimeLabel();
        float pillW = dp(56);
        float pillH = dp(20);
        float pillX = Math.max(left, Math.min(right - pillW, x - pillW / 2f));
        RectF pill = new RectF(pillX, lineY - dp(28), pillX + pillW, lineY - dp(8));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Colors.ACCENT);
        canvas.drawRoundRect(pill, dp(5), dp(5), paint);
        paint.setTextAlign(Paint.Align.CENTER);
        drawSans(canvas, time, pill.centerX(), pill.top + dp(14), dp(10), 0xffffffff, false);
        paint.setTextAlign(Paint.Align.LEFT);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x55ffffff);
        canvas.drawRoundRect(new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), dp(8), dp(8), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private String currentFrameLabel() {
        if (frames.isEmpty()) return "No screenshots saved for this card";
        int index = Math.max(0, Math.min(currentIndex, frames.size() - 1));
        ScreenshotRecord frame = frames.get(index);
        String app = frame.appLabel == null || frame.appLabel.trim().isEmpty() ? "Screenshot" : frame.appLabel;
        return app + " · " + (index + 1) + "/" + frames.size();
    }

    private String displayTimeLabel() {
        if (card == null || card.endMs <= card.startMs) return "";
        long time = card.startMs + Math.round(cardDurationMs() * progress);
        return TimeUtil.timeLabel(time).replace(" AM", "").replace(" PM", "");
    }

    private RectF centerCropRect(float sourceW, float sourceH, RectF bounds) {
        float scale = Math.max(bounds.width() / sourceW, bounds.height() / sourceH);
        float drawW = sourceW * scale;
        float drawH = sourceH * scale;
        return new RectF(
                bounds.centerX() - drawW / 2f,
                bounds.centerY() - drawH / 2f,
                bounds.centerX() + drawW / 2f,
                bounds.centerY() + drawH / 2f);
    }

    private float cardDurationMs() {
        return card == null ? 1f : Math.max(1f, card.endMs - card.startMs);
    }

    private void recycleCurrent() {
        if (currentBitmap != null) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setFakeBoldText(false);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color, boolean caps) {
        paint.setTypeface(DayflowType.sans(getContext(), caps));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setFakeBoldText(caps);
        c.drawText(text, x, y, paint);
        paint.setFakeBoldText(false);
    }
}

final class ReviewSummaryView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ReviewSnapshot snapshot = new ReviewSnapshot();

    ReviewSummaryView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    void setData(ReviewSnapshot snapshot) {
        this.snapshot = snapshot == null ? new ReviewSnapshot() : snapshot;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth();
        if (w <= 0) return;
        drawBars(canvas, w);
        drawLegend(canvas, w);
    }

    private void drawBars(Canvas canvas, float w) {
        long total = snapshot.reviewedDurationMs();
        boolean placeholder = total <= 0;
        long distracted = placeholder ? 1 : snapshot.distractedMs;
        long neutral = placeholder ? 1 : snapshot.neutralMs;
        long focused = placeholder ? 1 : snapshot.focusedMs;
        long sum = Math.max(1, distracted + neutral + focused);
        long[] values = new long[]{distracted, neutral, focused};
        int[][] colors = placeholder
                ? new int[][]{{0xfff0e8e2, 0xffe7ddd6}, {0xfff7f3ef, 0xffeae0db}, {0xfff0e8e2, 0xffe7ddd6}}
                : new int[][]{{0xffffbdb1, 0xffff8772}, {0xffffffff, 0xffeae0db}, {0xff92f1e3, 0xff42d0bb}};
        float gap = dp(4);
        float left = dp(2);
        float top = dp(12);
        float height = dp(39);
        float available = Math.max(1, w - dp(4) - gap * 2);
        float x = left;
        for (int i = 0; i < values.length; i++) {
            float width = i == values.length - 1 ? left + available + gap * 2 - x : available * values[i] / (float) sum;
            width = Math.max(placeholder ? dp(24) : 0, width);
            if (width <= 0.5f) continue;
            RectF rect = new RectF(x, top, Math.min(w - dp(2), x + width), top + height);
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, colors[i][0], colors[i][1], Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(rect, dp(4), dp(4), paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(colors[i][1]);
            canvas.drawRoundRect(rect, dp(4), dp(4), paint);
            paint.setStyle(Paint.Style.FILL);
            x += width + gap;
        }
    }

    private void drawLegend(Canvas canvas, float w) {
        String[] labels = new String[]{"Distracted", "Neutral", "Focused"};
        long[] values = new long[]{snapshot.distractedMs, snapshot.neutralMs, snapshot.focusedMs};
        int[] fills = new int[]{0x66ff8772, 0x66dddbda, 0x6642d0bb};
        int[] strokes = new int[]{0xffff8772, 0xffdddbda, 0xff42d0bb};
        float top = dp(78);
        float colW = w / labels.length;
        for (int i = 0; i < labels.length; i++) {
            float x = colW * i + dp(4);
            RectF swatch = new RectF(x, top - dp(10), x + dp(11), top - dp(2));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fills[i]);
            canvas.drawRoundRect(swatch, dp(3), dp(3), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(strokes[i]);
            canvas.drawRoundRect(swatch, dp(3), dp(3), paint);
            paint.setStyle(Paint.Style.FILL);
            drawSans(canvas, labels[i], x + dp(16), top - dp(2), dp(10), Colors.MUTED, false);
            String duration = snapshot.hasData() ? TimeUtil.shortDuration(values[i]) : "0m";
            drawSans(canvas, duration, x + dp(16), top + dp(18), dp(13), Colors.TEXT, true);
        }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color, boolean bold) {
        paint.setTypeface(DayflowType.sans(getContext(), bold));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setFakeBoldText(bold);
        c.drawText(text, x, y, paint);
        paint.setFakeBoldText(false);
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
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setFakeBoldText(false);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color, boolean caps) {
        paint.setTypeface(DayflowType.sans(getContext(), caps));
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
            drawSans(canvas, "Start recording and Dayflow will build cards after a full analysis batch.", labelW + dp(12), dp(112), dp(12), Colors.MUTED);
            return;
        }

        for (TimelineLayout layout : layoutCards(start, hourH, labelW)) {
            TimelineCard card = layout.card;
            RectF r = layout.rect;
            paint.setColor(ColorUtils.withAlpha(Colors.colorForCategory(card.category), 58));
            canvas.drawRoundRect(r, dp(12), dp(12), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f);
            paint.setColor(ColorUtils.withAlpha(Colors.colorForCategory(card.category), 160));
            canvas.drawRoundRect(r, dp(12), dp(12), paint);
            paint.setStyle(Paint.Style.FILL);

            canvas.save();
            canvas.clipRect(r.left + dp(6), r.top, r.right - dp(6), r.bottom);
            drawSans(canvas, fitText(card.title, r.width() - dp(24), dp(14)), r.left + dp(12), r.top + dp(22), dp(14), Colors.TEXT);
            if (r.height() > dp(54)) {
                drawSans(canvas, fitText(TimeUtil.shortDuration(card.durationMs()) + " · " + card.category, r.width() - dp(24), dp(11)), r.left + dp(12), r.top + dp(42), dp(11), Colors.MUTED);
            }
            canvas.restore();
        }
    }

    private List<TimelineLayout> layoutCards(long start, float hourH, float labelW) {
        List<TimelineCard> sorted = new ArrayList<>(cards);
        Collections.sort(sorted, new Comparator<TimelineCard>() {
            @Override public int compare(TimelineCard a, TimelineCard b) {
                int startCompare = Long.compare(a.startMs, b.startMs);
                if (startCompare != 0) return startCompare;
                return Long.compare(a.endMs, b.endMs);
            }
        });

        List<TimelineLayout> layouts = new ArrayList<>();
        float minHeight = dp(28);
        float gap = dp(4);
        float bottomLimit = 24f * hourH - dp(2);
        float lastBottom = -gap;
        for (TimelineCard card : sorted) {
            float rawTop = ((card.startMs - start) / (float) TimeUtil.HOUR) * hourH;
            float rawBottom = ((card.endMs - start) / (float) TimeUtil.HOUR) * hourH;
            float top = Math.max(0, rawTop);
            float bottom = Math.max(top + minHeight, rawBottom);
            if (top < lastBottom + gap) {
                top = lastBottom + gap;
                bottom = Math.max(top + minHeight, rawBottom);
            }
            bottom = Math.min(bottomLimit, bottom);
            if (bottom - top < dp(18)) continue;
            RectF rect = new RectF(labelW + dp(6), top + dp(3), getWidth() - dp(10), bottom - dp(3));
            if (rect.height() < dp(16) || rect.bottom <= 0 || rect.top >= bottomLimit) continue;
            layouts.add(new TimelineLayout(card, rect));
            lastBottom = rect.bottom;
        }
        return layouts;
    }

    private String fitText(String raw, float maxWidth, float textSize) {
        String value = raw == null || raw.trim().isEmpty() ? "Untitled" : raw.trim();
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(textSize);
        if (paint.measureText(value) <= maxWidth) return value;
        String suffix = "...";
        int end = value.length();
        while (end > 1 && paint.measureText(value.substring(0, end) + suffix) > maxWidth) end--;
        return value.substring(0, Math.max(1, end)) + suffix;
    }

    private static final class TimelineLayout {
        final TimelineCard card;
        final RectF rect;

        TimelineLayout(TimelineCard card, RectF rect) {
            this.card = card;
            this.rect = rect;
        }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
}

final class TimelineWeekCanvasView extends View {
    interface Listener {
        void onCardTapped(TimelineCard card);
        void onDayTapped(int dayIndex);
    }

    private static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<CardHit> hits = new ArrayList<>();
    private List<TimelineCard> cards = new ArrayList<>();
    private long weekStartMs = TimeUtil.weekStartMs(System.currentTimeMillis());
    private Listener listener;

    TimelineWeekCanvasView(Context context) {
        super(context);
        setMinimumHeight(dp(740));
        setWillNotDraw(false);
    }

    void setCards(long weekStartMs, List<TimelineCard> cards) {
        this.weekStartMs = weekStartMs;
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        invalidate();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override protected void onDraw(Canvas canvas) {
        hits.clear();
        float w = getWidth();
        float h = getHeight();
        drawPanel(canvas, 0, 0, w, h);
        drawSerif(canvas, "Week timeline", dp(18), dp(36), dp(24), Colors.TEXT);
        drawSans(canvas, "Tap a card to inspect it, or tap an empty day column to open that day.", dp(18), dp(58), dp(11), Colors.MUTED);

        float labelW = dp(36);
        float left = dp(14);
        float top = dp(86);
        float bottom = h - dp(18);
        float gridLeft = left + labelW;
        float gridW = Math.max(1, w - gridLeft - dp(12));
        float dayW = gridW / 7f;
        float gridH = Math.max(dp(480), bottom - top);
        float hourH = gridH / 24f;

        drawDayHeaders(canvas, gridLeft, dp(70), dayW);
        drawGrid(canvas, left, gridLeft, top, gridW, gridH, dayW, hourH);

        if (cards.isEmpty()) {
            drawSerif(canvas, "No cards yet", gridLeft + dp(12), top + dp(72), dp(24), Colors.TEXT);
            drawSans(canvas, "The week view fills in as Dayflow analyzes local screenshots.", gridLeft + dp(12), top + dp(100), dp(12), Colors.MUTED);
            return;
        }

        for (TimelineCard card : cards) {
            drawCardSegments(canvas, card, gridLeft, top, dayW, hourH);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
        for (int i = hits.size() - 1; i >= 0; i--) {
            CardHit hit = hits.get(i);
            if (hit.rect.contains(event.getX(), event.getY())) {
                if (listener != null) listener.onCardTapped(hit.card);
                return true;
            }
        }
        float labelW = dp(36);
        float gridLeft = dp(14) + labelW;
        float gridW = Math.max(1, getWidth() - gridLeft - dp(12));
        float dayW = gridW / 7f;
        float top = dp(86);
        float bottom = getHeight() - dp(18);
        if (event.getX() >= gridLeft && event.getX() <= gridLeft + gridW && event.getY() >= top && event.getY() <= bottom) {
            int day = Math.max(0, Math.min(6, (int) ((event.getX() - gridLeft) / dayW)));
            if (listener != null) listener.onDayTapped(day);
            return true;
        }
        return true;
    }

    private void drawDayHeaders(Canvas canvas, float gridLeft, float y, float dayW) {
        for (int day = 0; day < 7; day++) {
            float x = gridLeft + day * dayW;
            String dayKey = TimeUtil.dayKey(weekStartMs + day * TimeUtil.DAY + TimeUtil.HOUR);
            drawSans(canvas, DAYS[day], x + dp(6), y, dp(12), Colors.TEXT);
            drawSans(canvas, dayKey.substring(5), x + dp(6), y + dp(18), dp(10), Colors.MUTED);
        }
    }

    private void drawGrid(Canvas canvas, float left, float gridLeft, float top, float gridW, float gridH, float dayW, float hourH) {
        paint.setStrokeWidth(1f);
        for (int day = 0; day <= 7; day++) {
            float x = gridLeft + day * dayW;
            paint.setColor(ColorUtils.withAlpha(Colors.STROKE, day == 0 || day == 7 ? 190 : 90));
            canvas.drawLine(x, top, x, top + gridH, paint);
        }
        for (int hour = 0; hour <= 24; hour++) {
            float y = top + hour * hourH;
            paint.setColor(ColorUtils.withAlpha(Colors.MUTED, hour % 4 == 0 ? 86 : 36));
            canvas.drawLine(gridLeft, y, gridLeft + gridW, y, paint);
            if (hour % 2 == 0) {
                String label = String.format(LocaleSafe.US, "%02d", (hour + 4) % 24);
                drawSans(canvas, label, left, y + dp(4), dp(9), Colors.MUTED);
            }
        }
    }

    private void drawCardSegments(Canvas canvas, TimelineCard card, float gridLeft, float top, float dayW, float hourH) {
        for (int day = 0; day < 7; day++) {
            long dayStart = weekStartMs + day * TimeUtil.DAY;
            long dayEnd = dayStart + TimeUtil.DAY;
            long start = Math.max(card.startMs, dayStart);
            long end = Math.min(card.endMs, dayEnd);
            if (end <= start) continue;
            float segmentTop = top + ((start - dayStart) / (float) TimeUtil.HOUR) * hourH;
            float segmentBottom = top + ((end - dayStart) / (float) TimeUtil.HOUR) * hourH;
            segmentBottom = Math.max(segmentTop + dp(18), segmentBottom);
            RectF rect = new RectF(
                    gridLeft + day * dayW + dp(3),
                    segmentTop + dp(1),
                    gridLeft + (day + 1) * dayW - dp(3),
                    segmentBottom - dp(1));
            int categoryColor = Colors.colorForCategory(card.category);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ColorUtils.withAlpha(categoryColor, 56));
            canvas.drawRoundRect(rect, dp(6), dp(6), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.1f);
            paint.setColor(ColorUtils.withAlpha(categoryColor, 190));
            canvas.drawRoundRect(rect, dp(6), dp(6), paint);
            paint.setStyle(Paint.Style.FILL);

            canvas.save();
            canvas.clipRect(rect);
            drawSans(canvas, fit(card.title, rect.width(), dp(10)), rect.left + dp(5), rect.top + dp(13), dp(10), Colors.TEXT);
            if (rect.height() > dp(42)) {
                drawSans(canvas, TimeUtil.shortDuration(end - start), rect.left + dp(5), rect.top + dp(28), dp(9), Colors.MUTED);
            }
            canvas.restore();
            hits.add(new CardHit(new RectF(rect), card));
        }
    }

    private String fit(String raw, float width, float textSize) {
        String value = raw == null || raw.trim().isEmpty() ? "Untitled" : raw.trim();
        int maxChars = Math.max(4, (int) (width / Math.max(1, textSize * 0.58f)));
        if (value.length() <= maxChars) return value;
        return value.substring(0, Math.max(1, maxChars - 1)) + "...";
    }

    private void drawPanel(Canvas c, float x, float y, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Colors.CARD);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(16), dp(16), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        paint.setColor(Colors.STROKE);
        c.drawRoundRect(new RectF(x + 1, y + 1, x + w - 1, y + h - 1), dp(16), dp(16), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }

    private static final class CardHit {
        final RectF rect;
        final TimelineCard card;
        CardHit(RectF rect, TimelineCard card) {
            this.rect = rect;
            this.card = card;
        }
    }
}

final class DailyWorkflowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<TouchTarget> touchTargets = new ArrayList<>();
    private List<TimelineCard> cards = new ArrayList<>();
    private String day = TimeUtil.dayKey(System.currentTimeMillis());
    private String selectedDetail = "Tap a colored block to inspect the activity.";

    DailyWorkflowView(Context context) {
        super(context);
        setMinimumHeight(dp(520));
        setWillNotDraw(false);
    }

    void setCards(String day, List<TimelineCard> cards) {
        this.day = day;
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        this.selectedDetail = "Tap a colored block to inspect the activity.";
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        touchTargets.clear();
        DailyWorkflow workflow = computeWorkflow();
        drawPanel(canvas, 0, 0, getWidth(), getHeight());
        String heading = day.equals(TimeUtil.dayKey(System.currentTimeMillis()))
                ? "Today so far. Come back tomorrow for the full day view."
                : "Your workflow on " + day;
        drawSerif(canvas, fitText(heading, getWidth() - dp(36), dp(22), false), dp(18), dp(38), dp(22), Colors.ACCENT);
        drawSans(canvas, workflow.slotCount + " time blocks from " + hourLabel(workflow.visibleStart)
                + " to " + hourLabel(workflow.visibleEnd), dp(18), dp(62), dp(12), Colors.MUTED);

        float statsBottom = drawStats(canvas, workflow, dp(18), dp(78), getWidth() - dp(36));
        float gridTop = statsBottom + dp(16);
        drawWorkflowGrid(canvas, workflow, dp(14), gridTop, getWidth() - dp(28));
        drawTotals(canvas, workflow, dp(18), getHeight() - dp(82), getWidth() - dp(36));
        drawSelectedDetail(canvas, dp(18), getHeight() - dp(30), getWidth() - dp(36));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            for (TouchTarget target : touchTargets) {
                if (target.rect.contains(event.getX(), event.getY())) {
                    selectedDetail = target.detail;
                    invalidate();
                    return true;
                }
            }
            selectedDetail = "Tap a colored block to inspect the activity.";
            invalidate();
            return true;
        }
        return true;
    }

    private DailyWorkflow computeWorkflow() {
        DailyWorkflow workflow = new DailyWorkflow();
        long startMs = TimeUtil.dayStartMs(day);
        List<CardSegment> segments = new ArrayList<>();
        for (TimelineCard card : cards) {
            if (isSystem(card.category)) continue;
            int start = minuteFor(card.startMs, startMs);
            int end = Math.max(start + 1, minuteFor(card.endMs, startMs));
            segments.add(new CardSegment(card, start, end));
        }
        Collections.sort(segments, new Comparator<CardSegment>() {
            @Override public int compare(CardSegment a, CardSegment b) {
                if (a.startMinute != b.startMinute) return a.startMinute - b.startMinute;
                return a.endMinute - b.endMinute;
            }
        });

        if (segments.isEmpty()) {
            workflow.visibleStart = 9 * 60;
            workflow.visibleEnd = 21 * 60;
        } else {
            int first = Integer.MAX_VALUE;
            int last = Integer.MIN_VALUE;
            for (CardSegment segment : segments) {
                first = Math.min(first, segment.startMinute);
                last = Math.max(last, segment.endMinute);
            }
            int alignedStart = (int) Math.floor(first / 60f) * 60;
            int alignedEnd = (int) Math.ceil(last / 60f) * 60;
            workflow.visibleStart = alignedStart;
            workflow.visibleEnd = Math.max(alignedStart + 12 * 60, alignedEnd);
        }
        workflow.slotCount = Math.max(1, Math.round((workflow.visibleEnd - workflow.visibleStart) / 15f));

        seedRows(workflow);
        String previousKey = null;
        int previousEnd = -1;
        for (CardSegment segment : segments) {
            String key = normalized(segment.card.category);
            if (key.isEmpty()) key = "uncategorized";
            boolean distraction = isDistraction(segment.card.category);
            boolean idle = isIdle(segment.card.category);
            long duration = segment.card.durationMs();
            addDuration(workflow.totals, clean(segment.card.category, "Uncategorized"), duration);

            if (distraction) {
                workflow.interruptions++;
                workflow.distractedMs += duration;
                workflow.distractionMarkers.add(new Marker(segment));
            } else {
                Row row = rowFor(workflow, key, clean(segment.card.category, "Uncategorized"), Colors.colorForCategory(segment.card.category));
                fillOccupancy(row, segment, workflow);
                if (idle) workflow.distractedMs += duration;
                else workflow.focusedMs += duration;
            }

            if (previousKey != null && !previousKey.equals(key)) workflow.contextSwitches++;
            if (previousEnd >= 0 && segment.startMinute > previousEnd) {
                workflow.transitionMs += (segment.startMinute - previousEnd) * TimeUtil.MINUTE;
            }
            previousKey = key;
            previousEnd = Math.max(previousEnd, segment.endMinute);
        }
        return workflow;
    }

    private void seedRows(DailyWorkflow workflow) {
        rowFor(workflow, "work", "Work", Colors.WORK);
        rowFor(workflow, "communication", "Communication", Colors.COMMUNICATION);
        rowFor(workflow, "personal", "Personal", Colors.PERSONAL);
        rowFor(workflow, "idle", "Idle", Colors.IDLE);
    }

    private void fillOccupancy(Row row, CardSegment segment, DailyWorkflow workflow) {
        int clippedStart = Math.max(segment.startMinute, workflow.visibleStart);
        int clippedEnd = Math.min(segment.endMinute, workflow.visibleEnd);
        if (clippedEnd <= clippedStart) return;
        for (int i = 0; i < workflow.slotCount; i++) {
            int slotStart = workflow.visibleStart + i * 15;
            int slotEnd = Math.min(workflow.visibleEnd, slotStart + 15);
            int overlap = Math.max(0, Math.min(clippedEnd, slotEnd) - Math.max(clippedStart, slotStart));
            if (overlap <= 0) continue;
            row.occupancy[i] = Math.min(1f, row.occupancy[i] + overlap / 15f);
            if (row.info[i] == null || overlap > row.info[i].overlapMinutes) {
                row.info[i] = new SlotInfo(segment.card, overlap);
            }
        }
    }

    private float drawStats(Canvas canvas, DailyWorkflow workflow, float x, float y, float width) {
        String[][] stats = new String[][]{
                {"Context switched", countText(workflow.contextSwitches)},
                {"Interrupted", countText(workflow.interruptions)},
                {"Focused for", TimeUtil.shortDuration(workflow.focusedMs)},
                {"Distracted for", TimeUtil.shortDuration(workflow.distractedMs)},
                {"Transitioning time", TimeUtil.shortDuration(workflow.transitionMs)}
        };
        int columns = width >= dp(660) ? 5 : 2;
        float gap = dp(8);
        float chipW = (width - gap * (columns - 1)) / columns;
        float chipH = dp(42);
        for (int i = 0; i < stats.length; i++) {
            int row = i / columns;
            int col = i % columns;
            float left = x + col * (chipW + gap);
            float top = y + row * (chipH + gap);
            paint.setColor(Color.argb(132, 255, 255, 255));
            canvas.drawRoundRect(new RectF(left, top, left + chipW, top + chipH), dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(0xffebe6e3);
            canvas.drawRoundRect(new RectF(left, top, left + chipW, top + chipH), dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.FILL);
            drawSans(canvas, fitText(stats[i][0], chipW - dp(16), dp(10), false), left + dp(8), top + dp(15), dp(10), Colors.MUTED);
            drawSans(canvas, fitText(stats[i][1], chipW - dp(16), dp(14), true), left + dp(8), top + dp(32), dp(14), Colors.TEXT, true);
        }
        int rows = (int) Math.ceil(stats.length / (float) columns);
        return y + rows * chipH + Math.max(0, rows - 1) * gap;
    }

    private void drawWorkflowGrid(Canvas canvas, DailyWorkflow workflow, float x, float y, float width) {
        float labelW = Math.min(dp(118), Math.max(dp(78), width * 0.27f));
        float gridLeft = x + labelW + dp(13);
        float gridRight = x + width - dp(10);
        float gridW = Math.max(dp(80), gridRight - gridLeft);
        float rowH = dp(18);
        float gap = dp(3);
        float cellW = Math.max(1f, (gridW - gap * Math.max(0, workflow.slotCount - 1)) / workflow.slotCount);
        float top = y + dp(14);

        paint.setColor(Color.argb(138, 255, 255, 255));
        canvas.drawRoundRect(new RectF(x, y, x + width, y + gridHeight(workflow) + dp(74)), dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0xffebe6e3);
        canvas.drawRoundRect(new RectF(x, y, x + width, y + gridHeight(workflow) + dp(74)), dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.FILL);

        List<Row> visibleRows = visibleRows(workflow);
        for (int r = 0; r < visibleRows.size(); r++) {
            Row row = visibleRows.get(r);
            float yy = top + r * (rowH + gap);
            drawSans(canvas, fitText(row.name, labelW - dp(8), dp(11), false), x + dp(8), yy + dp(13), dp(11), Colors.TEXT);
            for (int i = 0; i < workflow.slotCount; i++) {
                float left = gridLeft + i * (cellW + gap);
                RectF cell = new RectF(left, yy, left + cellW, yy + rowH);
                paint.setColor(Color.argb(58, 242, 238, 234));
                canvas.drawRoundRect(cell, dp(2), dp(2), paint);
                if (row.occupancy[i] > 0f) {
                    int alpha = 76 + Math.round(179 * Math.min(1f, row.occupancy[i]));
                    paint.setColor(ColorUtils.withAlpha(row.color, alpha));
                    canvas.drawRoundRect(cell, dp(2), dp(2), paint);
                    if (row.info[i] != null) {
                        touchTargets.add(new TouchTarget(cell, detailFor(row.info[i].card, row.name)));
                    }
                }
            }
        }

        float markerTop = top + visibleRows.size() * (rowH + gap) + dp(6);
        if (!workflow.distractionMarkers.isEmpty()) {
            drawSans(canvas, "Distractions", x + dp(8), markerTop + dp(10), dp(11), Colors.TEXT);
            RectF base = new RectF(gridLeft, markerTop, gridRight, markerTop + dp(10));
            paint.setColor(Color.argb(72, 242, 238, 234));
            canvas.drawRoundRect(base, dp(2), dp(2), paint);
            int totalMinutes = Math.max(1, workflow.visibleEnd - workflow.visibleStart);
            for (Marker marker : workflow.distractionMarkers) {
                float left = gridLeft + ((marker.startMinute - workflow.visibleStart) / (float) totalMinutes) * gridW;
                float right = gridLeft + ((marker.endMinute - workflow.visibleStart) / (float) totalMinutes) * gridW;
                RectF rect = new RectF(Math.max(gridLeft, left), markerTop, Math.min(gridRight, Math.max(left + dp(3), right)), markerTop + dp(10));
                paint.setColor(ColorUtils.withAlpha(Colors.DISTRACTION, 220));
                canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                touchTargets.add(new TouchTarget(rect, "Distraction: " + marker.title + " / " + TimeUtil.shortDuration(marker.durationMs)));
            }
            markerTop += dp(18);
        }

        float axisY = markerTop + dp(10);
        paint.setColor(0xffe0d9d5);
        canvas.drawRect(gridLeft, axisY, gridRight, axisY + Math.max(1, dp(1)), paint);
        for (int hour = firstHour(workflow.visibleStart); hour <= lastHour(workflow.visibleEnd); hour++) {
            float fraction = (hour * 60 - workflow.visibleStart) / (float) Math.max(1, workflow.visibleEnd - workflow.visibleStart);
            if (fraction < -0.01f || fraction > 1.01f) continue;
            float xx = gridLeft + Math.max(0f, Math.min(1f, fraction)) * gridW;
            paint.setColor(ColorUtils.withAlpha(Colors.MUTED, 90));
            canvas.drawLine(xx, axisY - dp(4), xx, axisY + dp(4), paint);
            drawSans(canvas, hourLabel(hour * 60), Math.min(xx, gridRight - dp(26)), axisY + dp(18), dp(10), Colors.MUTED);
        }
    }

    private void drawTotals(Canvas canvas, DailyWorkflow workflow, float x, float y, float width) {
        drawSerif(canvas, day.equals(TimeUtil.dayKey(System.currentTimeMillis())) ? "Today's total so far" : "Day total", x, y, dp(15), Colors.MUTED);
        float cursor = x + dp(126);
        int shown = 0;
        for (Map.Entry<String, Long> entry : sortedTotals(workflow.totals)) {
            if (shown++ >= 4) break;
            String label = entry.getKey() + " " + TimeUtil.shortDuration(entry.getValue());
            int color = Colors.colorForCategory(entry.getKey());
            drawSans(canvas, fitText(label, Math.max(dp(72), width - (cursor - x)), dp(12), true), cursor, y, dp(12), color, true);
            cursor += Math.min(dp(150), Math.max(dp(82), paint.measureText(label) + dp(18)));
            if (cursor > x + width - dp(60)) break;
        }
        if (workflow.totals.isEmpty()) {
            drawSans(canvas, "No captured activity yet.", cursor, y, dp(12), Colors.MUTED);
        }
    }

    private void drawSelectedDetail(Canvas canvas, float x, float y, float width) {
        paint.setColor(Color.argb(132, 255, 255, 255));
        canvas.drawRoundRect(new RectF(x, y - dp(22), x + width, y + dp(10)), dp(10), dp(10), paint);
        drawSans(canvas, fitText(selectedDetail, width - dp(18), dp(12), false), x + dp(9), y - dp(1), dp(12), Colors.TEXT);
    }

    private float gridHeight(DailyWorkflow workflow) {
        return visibleRows(workflow).size() * (dp(18) + dp(3))
                + (workflow.distractionMarkers.isEmpty() ? 0 : dp(24)) + dp(42);
    }

    private List<Row> visibleRows(DailyWorkflow workflow) {
        List<Row> out = new ArrayList<>();
        for (Row row : workflow.rows.values()) {
            if ("distraction".equals(row.key)) continue;
            out.add(row);
        }
        return out;
    }

    private Row rowFor(DailyWorkflow workflow, String key, String name, int color) {
        Row row = workflow.rows.get(key);
        if (row == null) {
            row = new Row(key, name, color, workflow.slotCount);
            workflow.rows.put(key, row);
        }
        return row;
    }

    private List<Map.Entry<String, Long>> sortedTotals(Map<String, Long> totals) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(totals.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Long>>() {
            @Override public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        return entries;
    }

    private static void addDuration(Map<String, Long> map, String key, long value) {
        Long current = map.get(key);
        map.put(key, current == null ? value : current + value);
    }

    private int minuteFor(long timestampMs, long dayStartMs) {
        return Math.round((timestampMs - dayStartMs) / (float) TimeUtil.MINUTE) + 4 * 60;
    }

    private int firstHour(int minute) {
        return (int) Math.ceil(minute / 60f);
    }

    private int lastHour(int minute) {
        return (int) Math.floor(minute / 60f);
    }

    private String hourLabel(int minute) {
        int hour = ((minute / 60) % 24 + 24) % 24;
        if (hour == 0) return "12a";
        if (hour < 12) return hour + "a";
        if (hour == 12) return "12p";
        return (hour - 12) + "p";
    }

    private String detailFor(TimelineCard card, String rowName) {
        return TimeUtil.timeLabel(card.startMs) + " - " + TimeUtil.timeLabel(card.endMs)
                + " / " + rowName + " / " + clean(card.title, "Untitled activity");
    }

    private static String countText(int count) {
        return count == 1 ? "1 time" : count + " times";
    }

    private static boolean isSystem(String category) {
        return normalized(category).contains("system");
    }

    private static boolean isDistraction(String category) {
        return normalized(category).contains("distraction");
    }

    private static boolean isIdle(String category) {
        return normalized(category).contains("idle");
    }

    private static String normalized(String value) {
        return clean(value, "").toLowerCase(LocaleSafe.US).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private void drawPanel(Canvas c, float x, float y, float w, float h) {
        paint.setColor(Colors.CARD);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(18), dp(18), paint);
    }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }

    private void drawSans(Canvas c, String text, float x, float y, float size, int color, boolean bold) {
        paint.setTypeface(DayflowType.sans(getContext(), bold));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }

    private String fitText(String text, float width, float size, boolean bold) {
        String safe = text == null ? "" : text;
        paint.setTypeface(DayflowType.sans(getContext(), bold));
        paint.setTextSize(size);
        if (paint.measureText(safe) <= width) return safe;
        String suffix = "...";
        int end = safe.length();
        while (end > 1 && paint.measureText(safe.substring(0, end) + suffix) > width) end--;
        return safe.substring(0, Math.max(1, end)) + suffix;
    }

    private static final class DailyWorkflow {
        int visibleStart;
        int visibleEnd;
        int slotCount;
        int contextSwitches;
        int interruptions;
        long focusedMs;
        long distractedMs;
        long transitionMs;
        final LinkedHashMap<String, Row> rows = new LinkedHashMap<>();
        final LinkedHashMap<String, Long> totals = new LinkedHashMap<>();
        final List<Marker> distractionMarkers = new ArrayList<>();
    }

    private static final class Row {
        final String key;
        final String name;
        final int color;
        final float[] occupancy;
        final SlotInfo[] info;

        Row(String key, String name, int color, int slotCount) {
            this.key = key;
            this.name = name;
            this.color = color;
            this.occupancy = new float[slotCount];
            this.info = new SlotInfo[slotCount];
        }
    }

    private static final class SlotInfo {
        final TimelineCard card;
        final int overlapMinutes;

        SlotInfo(TimelineCard card, int overlapMinutes) {
            this.card = card;
            this.overlapMinutes = overlapMinutes;
        }
    }

    private static final class Marker {
        final String title;
        final int startMinute;
        final int endMinute;
        final long durationMs;

        Marker(CardSegment segment) {
            this.title = clean(segment.card.title, "Distraction");
            this.startMinute = segment.startMinute;
            this.endMinute = segment.endMinute;
            this.durationMs = segment.card.durationMs();
        }
    }

    private static final class CardSegment {
        final TimelineCard card;
        final int startMinute;
        final int endMinute;

        CardSegment(TimelineCard card, int startMinute, int endMinute) {
            this.card = card;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
        }
    }

    private static final class TouchTarget {
        final RectF rect;
        final String detail;

        TouchTarget(RectF rect, String detail) {
            this.rect = new RectF(rect);
            this.detail = detail;
        }
    }
}

final class WeeklyCanvasView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<TimelineCard> cards = new ArrayList<>();
    private long weekStartMs = TimeUtil.weekStartMs(System.currentTimeMillis());

    WeeklyCanvasView(Context context) {
        super(context);
        setMinimumHeight(dp(520));
    }

    void setCards(long weekStartMs, List<TimelineCard> cards) {
        this.weekStartMs = weekStartMs;
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPanel(canvas, 0, 0, getWidth(), dp(170));
        drawSerif(canvas, "Focus and distraction heat map", dp(18), dp(38), dp(23), Colors.TEXT);
        drawSans(canvas, TimeUtil.weekLabel(weekStartMs), getWidth() - dp(132), dp(38), dp(12), Colors.MUTED);
        drawHeatmap(canvas, dp(18), dp(62), getWidth() - dp(36), dp(78));
        drawPanel(canvas, 0, dp(190), getWidth(), dp(300));
        drawSerif(canvas, "Time distribution", dp(18), dp(230), dp(23), Colors.TEXT);
        drawDistribution(canvas, dp(18), dp(260), getWidth() - dp(36), dp(190));
    }

    private void drawHeatmap(Canvas c, float x, float y, float w, float h) {
        long start = weekStartMs;
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
        long start = weekStartMs;
        String[] labels = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int d = 0; d < 7; d++) {
            float yy = y + d * dp(34);
            drawSans(c, labels[d], x, yy + dp(13), dp(11), Colors.TEXT);
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
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
}

final class WeeklyTreemapView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<AppTile> drawnTiles = new ArrayList<>();
    private List<TimelineCard> cards = new ArrayList<>();
    private List<TimelineCard> previousCards = new ArrayList<>();
    private AppTile selectedTile;

    WeeklyTreemapView(Context context) {
        super(context);
        setMinimumHeight(dp(430));
        setWillNotDraw(false);
    }

    void setCards(List<TimelineCard> cards, List<TimelineCard> previousCards) {
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        this.previousCards = previousCards == null ? new ArrayList<TimelineCard>() : previousCards;
        selectedTile = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawnTiles.clear();
        drawPanel(canvas, 0, 0, getWidth(), getHeight());
        drawSerif(canvas, "Most used per category", dp(18), dp(38), dp(23), Colors.ACCENT);
        drawSans(canvas, "Apps grouped by category with prior-week deltas.", dp(18), dp(62), dp(12), Colors.MUTED);

        List<CategoryBucket> buckets = buildBuckets();
        if (buckets.isEmpty()) {
            drawEmpty(canvas, "Treemap fills in after Dayflow has analyzed app-labeled timeline cards.");
            return;
        }

        RectF content = new RectF(dp(14), dp(88), getWidth() - dp(14), getHeight() - dp(74));
        layoutCategories(buckets, content);
        for (CategoryBucket category : buckets) {
            drawCategory(canvas, category);
        }
        drawSelection(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        for (AppTile tile : drawnTiles) {
            if (tile.frame != null && tile.frame.contains(event.getX(), event.getY())) {
                selectedTile = tile;
                invalidate();
                return true;
            }
        }
        selectedTile = null;
        invalidate();
        return true;
    }

    private void drawCategory(Canvas canvas, CategoryBucket category) {
        paint.setColor(ColorUtils.withAlpha(category.color, 32));
        canvas.drawRoundRect(category.frame, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(ColorUtils.withAlpha(category.color, 145));
        canvas.drawRoundRect(category.frame, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.FILL);

        drawSans(canvas, fitText(category.name, category.frame.width() - dp(92), dp(13), true),
                category.frame.left + dp(10), category.frame.top + dp(20), dp(13), category.color);
        drawSans(canvas, TimeUtil.shortDuration(category.durationMs),
                category.frame.right - dp(72), category.frame.top + dp(20), dp(11), Colors.MUTED);

        for (AppTile tile : category.tiles) {
            drawTile(canvas, tile);
        }
    }

    private void drawTile(Canvas canvas, AppTile tile) {
        if (tile.frame == null || tile.frame.width() < dp(8) || tile.frame.height() < dp(8)) return;
        drawnTiles.add(tile);
        boolean selected = selectedTile != null && selectedTile.key.equals(tile.key);
        int fillAlpha = selected ? 235 : 170;
        paint.setColor(ColorUtils.withAlpha(tile.color, fillAlpha));
        canvas.drawRoundRect(tile.frame, dp(6), dp(6), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(selected ? dp(2) : dp(1));
        paint.setColor(selected ? Colors.TEXT : ColorUtils.withAlpha(tile.color, 190));
        canvas.drawRoundRect(tile.frame, dp(6), dp(6), paint);
        paint.setStyle(Paint.Style.FILL);

        if (tile.frame.width() > dp(46) && tile.frame.height() > dp(30)) {
            float textWidth = tile.frame.width() - dp(14);
            drawSans(canvas, fitText(tile.appName, textWidth, dp(12), true),
                    tile.frame.left + dp(7), tile.frame.top + dp(17), dp(12), Colors.TEXT);
            if (tile.frame.height() > dp(48)) {
                drawSans(canvas, TimeUtil.shortDuration(tile.durationMs),
                        tile.frame.left + dp(7), tile.frame.top + dp(35), dp(11), Colors.MUTED);
            }
        }
    }

    private void drawSelection(Canvas canvas) {
        AppTile tile = selectedTile;
        if (tile == null && !drawnTiles.isEmpty()) tile = drawnTiles.get(0);
        if (tile == null) return;
        RectF detail = new RectF(dp(14), getHeight() - dp(60), getWidth() - dp(14), getHeight() - dp(14));
        paint.setColor(Colors.CARD_ALT);
        canvas.drawRoundRect(detail, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Colors.STROKE);
        canvas.drawRoundRect(detail, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.FILL);

        String delta = deltaText(tile.durationMs - tile.previousDurationMs);
        drawSans(canvas, fitText(tile.appName + " / " + tile.categoryName, detail.width() - dp(28), dp(13), true),
                detail.left + dp(12), detail.top + dp(18), dp(13), Colors.TEXT);
        drawSans(canvas, TimeUtil.shortDuration(tile.durationMs) + " this week · " + delta + " vs previous",
                detail.left + dp(12), detail.top + dp(36), dp(12), delta.startsWith("+") ? Colors.WORK : Colors.MUTED);
    }

    private void drawEmpty(Canvas canvas, String message) {
        RectF box = new RectF(dp(18), dp(96), getWidth() - dp(18), dp(178));
        paint.setColor(Colors.CARD_ALT);
        canvas.drawRoundRect(box, dp(12), dp(12), paint);
        drawSans(canvas, message, box.left + dp(12), box.top + dp(38), dp(13), Colors.MUTED);
    }

    private List<CategoryBucket> buildBuckets() {
        Map<String, Long> previous = aggregateAppCategory(previousCards);
        Map<String, CategoryBucket> categories = new LinkedHashMap<>();
        for (TimelineCard card : cards) {
            if (isIgnored(card)) continue;
            String categoryName = clean(card.category, "Work");
            String appName = clean(appFromMetadata(card.metadata), "Unknown app");
            String categoryKey = categoryName.toLowerCase(LocaleSafe.US);
            String tileKey = categoryKey + "|" + appName.toLowerCase(LocaleSafe.US);
            CategoryBucket bucket = categories.get(categoryKey);
            if (bucket == null) {
                bucket = new CategoryBucket(categoryKey, categoryName, Colors.colorForCategory(categoryName));
                categories.put(categoryKey, bucket);
            }
            AppTile tile = bucket.tileByKey.get(tileKey);
            if (tile == null) {
                tile = new AppTile(tileKey, categoryName, appName, shadeForApp(appName, categoryName));
                tile.previousDurationMs = previous.containsKey(tileKey) ? previous.get(tileKey) : 0;
                bucket.tileByKey.put(tileKey, tile);
                bucket.tiles.add(tile);
            }
            long duration = card.durationMs();
            tile.durationMs += duration;
            bucket.durationMs += duration;
        }
        List<CategoryBucket> result = new ArrayList<>(categories.values());
        Collections.sort(result, new Comparator<CategoryBucket>() {
            @Override public int compare(CategoryBucket a, CategoryBucket b) {
                return Long.compare(b.durationMs, a.durationMs);
            }
        });
        if (result.size() > 5) result = new ArrayList<>(result.subList(0, 5));
        for (CategoryBucket bucket : result) {
            Collections.sort(bucket.tiles, new Comparator<AppTile>() {
                @Override public int compare(AppTile a, AppTile b) {
                    return Long.compare(b.durationMs, a.durationMs);
                }
            });
            if (bucket.tiles.size() > 8) {
                List<AppTile> visible = new ArrayList<>(bucket.tiles.subList(0, 7));
                AppTile other = new AppTile(bucket.key + "|other", bucket.name, "Other", Colors.IDLE);
                for (int i = 7; i < bucket.tiles.size(); i++) {
                    other.durationMs += bucket.tiles.get(i).durationMs;
                    other.previousDurationMs += bucket.tiles.get(i).previousDurationMs;
                }
                visible.add(other);
                bucket.tiles = visible;
            }
        }
        return result;
    }

    private Map<String, Long> aggregateAppCategory(List<TimelineCard> source) {
        Map<String, Long> map = new HashMap<>();
        for (TimelineCard card : source) {
            if (isIgnored(card)) continue;
            String categoryName = clean(card.category, "Work");
            String appName = clean(appFromMetadata(card.metadata), "Unknown app");
            String key = categoryName.toLowerCase(LocaleSafe.US) + "|" + appName.toLowerCase(LocaleSafe.US);
            Long current = map.get(key);
            map.put(key, current == null ? card.durationMs() : current + card.durationMs());
        }
        return map;
    }

    private void layoutCategories(List<CategoryBucket> categories, RectF rect) {
        List<WeightedFrame<CategoryBucket>> frames = new ArrayList<>();
        layoutWeighted(categories, rect, new Weight<CategoryBucket>() {
            @Override public long value(CategoryBucket item) { return item.durationMs; }
        }, frames, 0);
        for (WeightedFrame<CategoryBucket> frame : frames) {
            CategoryBucket category = frame.item;
            category.frame = inset(frame.frame, dp(3));
            RectF appsRect = new RectF(category.frame.left + dp(6), category.frame.top + dp(28),
                    category.frame.right - dp(6), category.frame.bottom - dp(6));
            List<WeightedFrame<AppTile>> appFrames = new ArrayList<>();
            layoutWeighted(category.tiles, appsRect, new Weight<AppTile>() {
                @Override public long value(AppTile item) { return item.durationMs; }
            }, appFrames, 0);
            for (WeightedFrame<AppTile> appFrame : appFrames) {
                appFrame.item.frame = inset(appFrame.frame, dp(2));
            }
        }
    }

    private <T> void layoutWeighted(List<T> items, RectF rect, Weight<T> weight, List<WeightedFrame<T>> out, int depth) {
        if (items.isEmpty() || rect.width() <= 0 || rect.height() <= 0) return;
        if (items.size() == 1) {
            out.add(new WeightedFrame<T>(items.get(0), new RectF(rect)));
            return;
        }
        long total = 0;
        for (T item : items) total += Math.max(1, weight.value(item));
        long half = total / 2;
        long running = 0;
        int split = 0;
        for (int i = 0; i < items.size() - 1; i++) {
            long next = running + Math.max(1, weight.value(items.get(i)));
            split = i + 1;
            running = next;
            if (next >= half) break;
        }
        float ratio = total <= 0 ? 0.5f : (float) running / (float) total;
        RectF first = new RectF(rect);
        RectF second = new RectF(rect);
        if ((depth % 2 == 0 && rect.width() >= rect.height()) || rect.width() > rect.height() * 1.35f) {
            first.right = rect.left + rect.width() * ratio;
            second.left = first.right;
        } else {
            first.bottom = rect.top + rect.height() * ratio;
            second.top = first.bottom;
        }
        layoutWeighted(items.subList(0, split), first, weight, out, depth + 1);
        layoutWeighted(items.subList(split, items.size()), second, weight, out, depth + 1);
    }

    private static RectF inset(RectF rect, float inset) {
        return new RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
    }

    private static String deltaText(long deltaMs) {
        if (Math.abs(deltaMs) < TimeUtil.MINUTE) return "flat";
        return (deltaMs >= 0 ? "+" : "-") + TimeUtil.shortDuration(Math.abs(deltaMs));
    }

    private static boolean isIgnored(TimelineCard card) {
        String category = card.category == null ? "" : card.category.toLowerCase(LocaleSafe.US);
        return category.contains("idle") || category.contains("system");
    }

    private static int shadeForApp(String appName, String categoryName) {
        int base = Colors.colorForCategory(categoryName);
        int shift = Math.abs((appName == null ? "" : appName).hashCode()) % 32;
        int r = Math.min(255, Color.red(base) + shift);
        int g = Math.max(0, Color.green(base) - shift / 2);
        int b = Math.min(255, Color.blue(base) + shift / 3);
        return Color.rgb(r, g, b);
    }

    private void drawPanel(Canvas c, float x, float y, float w, float h) {
        paint.setColor(Colors.CARD);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(16), dp(16), paint);
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
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

    private static String appFromMetadata(String metadata) {
        if (metadata == null) return null;
        String marker = "app=";
        int start = metadata.indexOf(marker);
        if (start < 0) return null;
        int end = metadata.indexOf(';', start);
        String app = end > start ? metadata.substring(start + marker.length(), end) : metadata.substring(start + marker.length());
        app = app.trim();
        return app.isEmpty() ? null : app;
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private interface Weight<T> {
        long value(T item);
    }

    private static final class WeightedFrame<T> {
        final T item;
        final RectF frame;
        WeightedFrame(T item, RectF frame) {
            this.item = item;
            this.frame = frame;
        }
    }

    private static final class CategoryBucket {
        final String key;
        final String name;
        final int color;
        long durationMs;
        RectF frame = new RectF();
        List<AppTile> tiles = new ArrayList<>();
        final Map<String, AppTile> tileByKey = new LinkedHashMap<>();
        CategoryBucket(String key, String name, int color) {
            this.key = key;
            this.name = name;
            this.color = color;
        }
    }

    private static final class AppTile {
        final String key;
        final String categoryName;
        final String appName;
        final int color;
        long durationMs;
        long previousDurationMs;
        RectF frame;
        AppTile(String key, String categoryName, String appName, int color) {
            this.key = key;
            this.categoryName = categoryName;
            this.appName = appName;
            this.color = color;
        }
    }
}

final class WeeklyInteractionGraphView extends View {
    private static final float[][] POSITIONS = new float[][]{
            {256.5f, 253.3f}, {106.5f, 411.3f}, {134f, 154.3f}, {220f, 136.8f},
            {308.5f, 167.8f}, {342.5f, 108.8f}, {436.1f, 142.8f}, {501.5f, 255.8f},
            {391.1f, 310.8f}, {391.5f, 415.8f}, {296f, 380.3f}, {62f, 304.8f},
            {111f, 233.8f}, {179.5f, 343.3f}
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<TimelineCard> cards = new ArrayList<>();
    private List<AppNode> nodes = new ArrayList<>();
    private List<AppEdge> edges = new ArrayList<>();
    private AppNode selectedNode;

    WeeklyInteractionGraphView(Context context) {
        super(context);
        setMinimumHeight(dp(520));
        setWillNotDraw(false);
    }

    void setCards(List<TimelineCard> cards) {
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        selectedNode = null;
        rebuild();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPanel(canvas, 0, 0, getWidth(), getHeight());
        drawSerif(canvas, "Interactions between most used applications", dp(18), dp(38), dp(21), Colors.ACCENT);
        drawSans(canvas, subtitle(), dp(18), dp(60), dp(12), Colors.MUTED);
        if (nodes.isEmpty()) {
            drawEmpty(canvas, "Application relationships appear after Usage Access and analyzed timeline cards are available.");
            return;
        }

        RectF network = new RectF(dp(12), dp(78), getWidth() - dp(12), getHeight() - dp(118));
        placeNodes(network);
        drawEdges(canvas);
        drawNodes(canvas);
        drawLegend(canvas, network);
        drawPatterns(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        AppNode best = null;
        float bestDistance = Float.MAX_VALUE;
        for (AppNode node : nodes) {
            float dx = event.getX() - node.cx;
            float dy = event.getY() - node.cy;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance <= node.radius + dp(12) && distance < bestDistance) {
                best = node;
                bestDistance = distance;
            }
        }
        selectedNode = best;
        invalidate();
        return true;
    }

    private void rebuild() {
        Map<String, AppNode> nodeMap = new LinkedHashMap<>();
        List<TimelineCard> sorted = new ArrayList<>(cards);
        Collections.sort(sorted, new Comparator<TimelineCard>() {
            @Override public int compare(TimelineCard a, TimelineCard b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        for (TimelineCard card : sorted) {
            if (isIgnored(card)) continue;
            String app = clean(appFromMetadata(card.metadata), "Unknown app");
            String key = normalized(app);
            AppNode node = nodeMap.get(key);
            if (node == null) {
                node = new AppNode(key, app, appKind(app, card.category), shadeForApp(app, card.category));
                nodeMap.put(key, node);
            }
            node.durationMs += card.durationMs();
            node.visits++;
            if (node.kind != Kind.DISTRACTION && appKind(app, card.category) == Kind.DISTRACTION) node.kind = Kind.DISTRACTION;
        }
        nodes = new ArrayList<>(nodeMap.values());
        Collections.sort(nodes, new Comparator<AppNode>() {
            @Override public int compare(AppNode a, AppNode b) {
                return Long.compare(b.durationMs, a.durationMs);
            }
        });
        if (nodes.size() > 14) nodes = new ArrayList<>(nodes.subList(0, 14));
        long maxDuration = 1;
        Set<String> visibleKeys = new HashSet<>();
        for (AppNode node : nodes) {
            maxDuration = Math.max(maxDuration, node.durationMs);
            visibleKeys.add(node.key);
        }
        for (int i = 0; i < nodes.size(); i++) {
            AppNode node = nodes.get(i);
            node.primary = i == 0;
            node.muted = i > 5;
            node.radius = dp(i == 0 ? 38 : 17 + (float) Math.sqrt((double) node.durationMs / (double) maxDuration) * 14f);
        }
        buildEdges(sorted, visibleKeys);
    }

    private void buildEdges(List<TimelineCard> sorted, Set<String> visibleKeys) {
        Map<String, AppEdge> edgeMap = new LinkedHashMap<>();
        String previousDay = null;
        String previousKey = null;
        for (TimelineCard card : sorted) {
            if (isIgnored(card)) continue;
            String app = clean(appFromMetadata(card.metadata), "Unknown app");
            String key = normalized(app);
            if (!visibleKeys.contains(key)) continue;
            String day = card.day == null ? TimeUtil.dayKey(card.startMs) : card.day;
            if (previousKey != null && previousDay != null && previousDay.equals(day) && !previousKey.equals(key)) {
                String edgeKey = previousKey + "|" + key;
                AppEdge edge = edgeMap.get(edgeKey);
                if (edge == null) {
                    edge = new AppEdge(previousKey, key);
                    edgeMap.put(edgeKey, edge);
                }
                edge.count++;
            }
            previousKey = key;
            previousDay = day;
        }
        edges = new ArrayList<>(edgeMap.values());
        Collections.sort(edges, new Comparator<AppEdge>() {
            @Override public int compare(AppEdge a, AppEdge b) {
                return Integer.compare(b.count, a.count);
            }
        });
        if (edges.size() > 18) edges = new ArrayList<>(edges.subList(0, 18));
    }

    private void placeNodes(RectF network) {
        float maxX = 565f;
        float maxY = 430f;
        for (int i = 0; i < nodes.size(); i++) {
            AppNode node = nodes.get(i);
            float[] pos = POSITIONS[i % POSITIONS.length];
            node.cx = network.left + (pos[0] / maxX) * network.width();
            node.cy = network.top + ((pos[1] - 70f) / maxY) * network.height();
            node.cy = Math.max(network.top + node.radius, Math.min(network.bottom - node.radius, node.cy));
        }
    }

    private void drawEdges(Canvas canvas) {
        int max = 1;
        Map<String, AppNode> byKey = nodeByKey();
        for (AppEdge edge : edges) max = Math.max(max, edge.count);
        int index = 0;
        for (AppEdge edge : edges) {
            AppNode from = byKey.get(edge.fromKey);
            AppNode to = byKey.get(edge.toKey);
            if (from == null || to == null) continue;
            boolean active = selectedNode == null || selectedNode.key.equals(from.key) || selectedNode.key.equals(to.key);
            Kind kind = edgeKind(from.kind, to.kind);
            paint.setColor(ColorUtils.withAlpha(colorForKind(kind), active ? 150 : 36));
            paint.setStrokeWidth(dp(1.2f + 4.2f * ((float) edge.count / (float) max)));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            Path path = new Path();
            path.moveTo(from.cx, from.cy);
            float curve = ((index % 2 == 0) ? -1 : 1) * dp(28 + (index % 5) * 7);
            path.quadTo((from.cx + to.cx) / 2f, (from.cy + to.cy) / 2f + curve, to.cx, to.cy);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            index++;
        }
    }

    private void drawNodes(Canvas canvas) {
        for (AppNode node : nodes) {
            boolean selected = selectedNode != null && selectedNode.key.equals(node.key);
            boolean active = selectedNode == null || selected;
            int alpha = active ? (node.muted ? 160 : 245) : 70;
            paint.setColor(ColorUtils.withAlpha(fillForKind(node.kind), alpha));
            canvas.drawCircle(node.cx, node.cy, node.radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(selected || node.primary ? dp(3) : dp(2));
            paint.setColor(ColorUtils.withAlpha(colorForKind(node.kind), active ? 235 : 90));
            canvas.drawCircle(node.cx, node.cy, node.radius, paint);
            paint.setStyle(Paint.Style.FILL);
            drawCentered(canvas, initial(node.name), node.cx, node.cy + dp(5), node.primary ? dp(20) : dp(13),
                    node.kind == Kind.WORK ? Colors.WORK : Colors.MUTED, true);
            if (selected || node.primary) {
                drawCentered(canvas, fitText(node.name, dp(120), dp(12), true), node.cx, node.cy + node.radius + dp(16),
                        dp(12), Colors.TEXT, true);
            }
        }
    }

    private void drawLegend(Canvas canvas, RectF network) {
        float y = network.bottom + dp(18);
        legend(canvas, "Work", Colors.WORK, network.left + dp(10), y);
        legend(canvas, "Personal", Colors.PERSONAL, network.left + dp(104), y);
        legend(canvas, "Distraction", Colors.DISTRACTION, network.left + dp(220), y);
    }

    private void legend(Canvas canvas, String label, int color, float x, float y) {
        paint.setColor(ColorUtils.withAlpha(color, 70));
        canvas.drawRoundRect(new RectF(x, y - dp(12), x + dp(14), y), dp(2), dp(2), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(color);
        canvas.drawRoundRect(new RectF(x, y - dp(12), x + dp(14), y), dp(2), dp(2), paint);
        paint.setStyle(Paint.Style.FILL);
        drawSans(canvas, label, x + dp(20), y, dp(12), Colors.TEXT);
    }

    private void drawPatterns(Canvas canvas) {
        RectF detail = new RectF(dp(14), getHeight() - dp(78), getWidth() - dp(14), getHeight() - dp(14));
        paint.setColor(Colors.CARD_ALT);
        canvas.drawRoundRect(detail, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Colors.STROKE);
        canvas.drawRoundRect(detail, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.FILL);

        String line;
        if (selectedNode != null) {
            line = selectedNode.name + " · " + TimeUtil.shortDuration(selectedNode.durationMs)
                    + " · " + selectedNode.visits + " visits";
            String switches = switchesFor(selectedNode);
            if (!switches.isEmpty()) line += "\n" + switches;
        } else if (!edges.isEmpty()) {
            AppEdge top = edges.get(0);
            Map<String, AppNode> byKey = nodeByKey();
            AppNode from = byKey.get(top.fromKey);
            AppNode to = byKey.get(top.toKey);
            line = "Most common switch: " + (from == null ? top.fromKey : from.name)
                    + " -> " + (to == null ? top.toKey : to.name) + " · " + top.count + "x";
        } else {
            line = "No repeated switches yet. Capture more cards to reveal weekly patterns.";
        }
        String[] parts = line.split("\\n", 2);
        drawSans(canvas, fitText(parts[0], detail.width() - dp(24), dp(13), true),
                detail.left + dp(12), detail.top + dp(22), dp(13), Colors.TEXT);
        if (parts.length > 1) {
            drawSans(canvas, fitText(parts[1], detail.width() - dp(24), dp(12), false),
                    detail.left + dp(12), detail.top + dp(44), dp(12), Colors.MUTED);
        }
    }

    private String switchesFor(AppNode node) {
        Map<String, AppNode> byKey = nodeByKey();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (AppEdge edge : edges) {
            if (!node.key.equals(edge.fromKey) && !node.key.equals(edge.toKey)) continue;
            AppNode from = byKey.get(edge.fromKey);
            AppNode to = byKey.get(edge.toKey);
            if (from == null || to == null) continue;
            if (count++ > 0) sb.append(" · ");
            sb.append(from.name).append(" -> ").append(to.name).append(" ").append(edge.count).append("x");
            if (count >= 2) break;
        }
        return sb.toString();
    }

    private String subtitle() {
        long visible = 0;
        long total = 0;
        for (TimelineCard card : cards) {
            if (isIgnored(card)) continue;
            total += card.durationMs();
        }
        for (AppNode node : nodes) visible += node.durationMs;
        int coverage = total <= 0 ? 0 : Math.round((float) visible / (float) total * 100f);
        return "About " + coverage + "% of recorded app time is represented by these nodes.";
    }

    private void drawEmpty(Canvas canvas, String message) {
        RectF box = new RectF(dp(18), dp(96), getWidth() - dp(18), dp(178));
        paint.setColor(Colors.CARD_ALT);
        canvas.drawRoundRect(box, dp(12), dp(12), paint);
        drawSans(canvas, message, box.left + dp(12), box.top + dp(38), dp(13), Colors.MUTED);
    }

    private Map<String, AppNode> nodeByKey() {
        Map<String, AppNode> byKey = new HashMap<>();
        for (AppNode node : nodes) byKey.put(node.key, node);
        return byKey;
    }

    private static Kind edgeKind(Kind from, Kind to) {
        if (from == Kind.DISTRACTION || to == Kind.DISTRACTION) return Kind.DISTRACTION;
        if (from == Kind.PERSONAL || to == Kind.PERSONAL) return Kind.PERSONAL;
        return Kind.WORK;
    }

    private static Kind appKind(String appName, String categoryName) {
        String text = (appName + " " + (categoryName == null ? "" : categoryName)).toLowerCase(LocaleSafe.US);
        if (text.contains("distraction") || text.contains("youtube") || text.contains("reddit")
                || text.contains("twitter") || text.contains("tiktok") || text.contains("netflix")
                || text.contains("steam") || text.contains("game") || text.equals("x")) {
            return Kind.DISTRACTION;
        }
        if (text.contains("personal") || text.contains("shopping") || text.contains("maps")
                || text.contains("messages") || text.contains("photos") || text.contains("music")) {
            return Kind.PERSONAL;
        }
        return Kind.WORK;
    }

    private static int colorForKind(Kind kind) {
        if (kind == Kind.DISTRACTION) return Colors.DISTRACTION;
        if (kind == Kind.PERSONAL) return Colors.PERSONAL;
        return Colors.WORK;
    }

    private static int fillForKind(Kind kind) {
        if (kind == Kind.DISTRACTION) return Color.rgb(255, 220, 207);
        if (kind == Kind.PERSONAL) return Color.rgb(230, 230, 230);
        return Color.rgb(238, 243, 255);
    }

    private static int shadeForApp(String appName, String categoryName) {
        int base = Colors.colorForCategory(categoryName);
        int shift = Math.abs((appName == null ? "" : appName).hashCode()) % 32;
        int r = Math.min(255, Color.red(base) + shift);
        int g = Math.max(0, Color.green(base) - shift / 2);
        int b = Math.min(255, Color.blue(base) + shift / 3);
        return Color.rgb(r, g, b);
    }

    private static String normalized(String value) {
        return clean(value, "unknown").toLowerCase(LocaleSafe.US).replace(' ', '-');
    }

    private static boolean isIgnored(TimelineCard card) {
        String category = card.category == null ? "" : card.category.toLowerCase(LocaleSafe.US);
        return category.contains("idle") || category.contains("system");
    }

    private static String appFromMetadata(String metadata) {
        if (metadata == null) return null;
        String marker = "app=";
        int start = metadata.indexOf(marker);
        if (start < 0) return null;
        int end = metadata.indexOf(';', start);
        String app = end > start ? metadata.substring(start + marker.length(), end) : metadata.substring(start + marker.length());
        app = app.trim();
        return app.isEmpty() ? null : app;
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String initial(String value) {
        String clean = clean(value, "?");
        return clean.substring(0, 1).toUpperCase(LocaleSafe.US);
    }

    private void drawPanel(Canvas c, float x, float y, float w, float h) {
        paint.setColor(Colors.CARD);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(16), dp(16), paint);
    }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(size);
        paint.setColor(color);
        c.drawText(text, x, y, paint);
    }
    private void drawCentered(Canvas c, String text, float x, float y, float size, int color, boolean bold) {
        paint.setTypeface(DayflowType.sans(getContext(), bold));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, x, y, paint);
        paint.setTextAlign(Paint.Align.LEFT);
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

    private enum Kind { WORK, PERSONAL, DISTRACTION }

    private static final class AppNode {
        final String key;
        final String name;
        Kind kind;
        final int color;
        long durationMs;
        int visits;
        float cx;
        float cy;
        float radius;
        boolean primary;
        boolean muted;
        AppNode(String key, String name, Kind kind, int color) {
            this.key = key;
            this.name = name;
            this.kind = kind;
            this.color = color;
        }
    }

    private static final class AppEdge {
        final String fromKey;
        final String toKey;
        int count;
        AppEdge(String fromKey, String toKey) {
            this.fromKey = fromKey;
            this.toKey = toKey;
        }
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
