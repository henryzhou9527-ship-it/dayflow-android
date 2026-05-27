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
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        opts.inSampleSize = Math.max(1, longest / 900);
        return BitmapFactory.decodeFile(path, opts);
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
