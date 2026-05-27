package com.henry.dayflow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CaptureService extends Service {
    static final String ACTION_START = "com.henry.dayflow.START";
    static final String ACTION_STOP = "com.henry.dayflow.STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "dayflow_capture";
    private static final int NOTIFICATION_ID = 7401;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DayflowDatabase db;
    private DayflowPrefs prefs;
    private ForegroundAppReader appReader;
    private AnalysisEngine analysisEngine;
    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private int captureWidth;
    private int captureHeight;
    private int densityDpi;
    private boolean running;
    private long lastPurgeMs;

    private final Runnable captureTick = new Runnable() {
        @Override
        public void run() {
            prefs.markCaptureHeartbeat();
            if (!prefs.isPaused()) captureLatestImage();
            purgeIfNeeded();
            if (running) handler.postDelayed(this, prefs.screenshotIntervalMs());
        }
    };

    private final Runnable analysisTick = new Runnable() {
        @Override
        public void run() {
            if (prefs.isPaused()) {
                if (running) handler.postDelayed(this, TimeUtil.MINUTE);
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    analysisEngine.processNow();
                }
            }, "dayflow-analysis").start();
            if (running) handler.postDelayed(this, TimeUtil.MINUTE);
        }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            stopCapture(false);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        db = new DayflowDatabase(this);
        prefs = new DayflowPrefs(this);
        appReader = new ForegroundAppReader(this);
        analysisEngine = new AnalysisEngine(this);
        prefs.markCaptureServiceStarted();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopCapture(true);
            return START_NOT_STICKY;
        }

        startForegroundCompat();

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultCode != 0 && resultData != null) {
                startProjection(resultCode, resultData);
            }
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopCapture(false);
        super.onDestroy();
    }

    private void startProjection(int resultCode, Intent resultData) {
        stopProjectionOnly();
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            prefs.markCaptureError("MediaProjectionManager unavailable");
            return;
        }
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            prefs.markCaptureError("Screen capture permission result was unavailable");
            return;
        }
        projection.registerCallback(projectionCallback, handler);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            prefs.markCaptureError("Window manager unavailable");
            return;
        }
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        captureWidth = metrics.widthPixels;
        captureHeight = metrics.heightPixels;
        densityDpi = metrics.densityDpi;
        prefs.markCaptureProjectionStarted(captureWidth, captureHeight);

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "DayflowCapture",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler);

        running = true;
        refreshNotification();
        handler.removeCallbacks(captureTick);
        handler.removeCallbacks(analysisTick);
        handler.postDelayed(captureTick, 800);
        handler.postDelayed(analysisTick, 5_000);
    }

    private void captureLatestImage() {
        prefs.markCaptureAttempt();
        if (imageReader == null) {
            prefs.markCaptureError("Image reader is not ready");
            return;
        }
        Image image = null;
        try {
            ForegroundAppReader.AppSnapshot app = appReader.currentApp();
            AccessibilityContextService.Snapshot window = AccessibilityContextService.latest(this);
            if (app.packageName == null && window.packageName != null && !window.packageName.trim().isEmpty()) {
                app = new ForegroundAppReader.AppSnapshot(window.packageName, appReader.labelFor(window.packageName));
            }
            boolean blocked = db.isBlockedApp(app.packageName);
            image = imageReader.acquireLatestImage();
            if (image == null) return;
            Bitmap bitmap = blocked ? redactedBitmap(app) : bitmapFromImage(image);
            Bitmap scaled = scaleForStorage(bitmap);
            if (scaled != bitmap) bitmap.recycle();

            File file = nextScreenshotFile();
            ScreenshotStorage.writeEncryptedJpeg(scaled, file, 85);
            scaled.recycle();

            db.insertScreenshot(
                    file,
                    System.currentTimeMillis(),
                    app.packageName,
                    app.label,
                    window.title,
                    window.text);
            prefs.markCaptureSuccess(app.packageName, app.label, file.length());
            refreshNotification();
        } catch (Exception error) {
            prefs.markCaptureError(error.getClass().getSimpleName() + ": " + error.getMessage());
            refreshNotification();
        } finally {
            if (image != null) image.close();
        }
    }

    private Bitmap bitmapFromImage(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * captureWidth;
        Bitmap padded = Bitmap.createBitmap(
                captureWidth + rowPadding / pixelStride,
                captureHeight,
                Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight);
        padded.recycle();
        return cropped;
    }

    private Bitmap scaleForStorage(Bitmap source) {
        int targetHeight = Math.min(source.getHeight(), 1080);
        if (targetHeight == source.getHeight()) return source;
        int targetWidth = Math.max(2, Math.round(source.getWidth() * (targetHeight / (float) source.getHeight())));
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
    }

    private File nextScreenshotFile() {
        File dir = new File(getFilesDir(), "recordings");
        if (!dir.exists()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(new Date());
        return new File(dir, stamp + ".dfjpg");
    }

    private Bitmap redactedBitmap(ForegroundAppReader.AppSnapshot app) {
        int width = Math.max(320, Math.min(captureWidth, 720));
        int height = Math.max(320, Math.round(width * (captureHeight / (float) Math.max(1, captureWidth))));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(Colors.BACKGROUND_TOP);
        RectF card = new RectF(width * 0.12f, height * 0.35f, width * 0.88f, height * 0.65f);
        paint.setColor(Colors.CARD);
        canvas.drawRoundRect(card, 24, 24, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(Colors.STROKE);
        canvas.drawRoundRect(card, 24, 24, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(DayflowType.serif(this));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(24f, width * 0.052f));
        paint.setColor(Colors.TEXT);
        canvas.drawText("Private app hidden", width / 2f, height * 0.48f, paint);
        paint.setTypeface(DayflowType.sans(this, false));
        paint.setTextSize(Math.max(12f, width * 0.026f));
        paint.setColor(Colors.MUTED);
        String label = app.label == null ? "Blocked capture" : app.label + " capture blocked";
        canvas.drawText(label, width / 2f, height * 0.55f, paint);
        return bitmap;
    }

    private void purgeIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastPurgeMs < 6 * TimeUtil.HOUR) return;
        lastPurgeMs = now;
        long cutoff = now - prefs.retentionDays() * TimeUtil.DAY;
        db.purgeScreenshotsOlderThan(cutoff);
    }

    private void stopCapture(boolean stopSelf) {
        running = false;
        handler.removeCallbacks(captureTick);
        handler.removeCallbacks(analysisTick);
        stopProjectionOnly();
        prefs.markCaptureStopped(stopSelf ? "Stopped by user" : "Projection stopped");
        refreshNotification();
        if (stopSelf) {
            stopForeground(true);
            stopSelf();
        }
    }

    private void stopProjectionOnly() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                12,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.presence_online)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(captureNotificationText())
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build();
    }

    private String captureNotificationText() {
        if (prefs.isPaused()) return prefs.pauseLabel();
        CaptureHealth health = prefs.captureHealth();
        if (health.hasNewerError()) return "Capture needs attention: " + shortText(health.lastError, 48);
        if (health.lastCaptureAtMs > 0) return "Last capture " + TimeUtil.timeLabel(health.lastCaptureAtMs) + ". Screenshots stay local.";
        return getString(R.string.capture_notification_text);
    }

    private void refreshNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private static String shortText(String value, int max) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        while (clean.contains("  ")) clean = clean.replace("  ", " ");
        if (clean.length() <= max) return clean;
        return clean.substring(0, Math.max(1, max - 3)).trim() + "...";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
