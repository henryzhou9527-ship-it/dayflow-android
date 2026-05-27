package com.henry.dayflow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
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
