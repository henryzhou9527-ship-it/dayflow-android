package com.henry.dayflow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class TimelapseGenerator {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int FPS = 6;
    private static final int MAX_OUTPUT_HEIGHT = 720;
    private static final int BIT_RATE = 2_000_000;
    private static final int MAX_FRAMES = 180;

    private final Context context;

    TimelapseGenerator(Context context) {
        this.context = context.getApplicationContext();
    }

    File generateForCard(DayflowDatabase db, TimelineCard card) throws IOException {
        List<ScreenshotRecord> screenshots = db.screenshotsInRange(
                card.startMs - TimeUtil.MINUTE,
                card.endMs + TimeUtil.MINUTE,
                MAX_FRAMES);
        File output = outputFile(card);
        generate(screenshots, output);
        db.updateTimelineCardVideoPath(card.id, output.getAbsolutePath());
        purgeToLimit(context, new DayflowPrefs(context).timelapseLimitBytes());
        return output;
    }

    private void generate(List<ScreenshotRecord> sourceScreenshots, File output) throws IOException {
        List<ScreenshotRecord> screenshots = existingScreenshots(sourceScreenshots);
        if (screenshots.isEmpty()) throw new IOException("No saved screenshots for this card");
        if (screenshots.size() == 1) screenshots.add(screenshots.get(0));

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds = ScreenshotStorage.decodeBounds(screenshots.get(0).filePath);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("First screenshot is unreadable");

        int[] size = canvasSize(bounds.outWidth, bounds.outHeight);
        int width = size[0];
        int height = size[1];
        int colorFormat = selectColorFormat();
        boolean semiPlanar = colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;

        if (!output.getParentFile().exists() && !output.getParentFile().mkdirs()) {
            throw new IOException("Could not create timelapse folder");
        }
        if (output.exists() && !output.delete()) throw new IOException("Could not replace old timelapse");

        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        int trackIndex = -1;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            encoder = MediaCodec.createEncoderByType(MIME);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int frameIndex = 0;

            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = encoder.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer buffer = encoder.getInputBuffer(inputIndex);
                        if (buffer == null) throw new IOException("Encoder input buffer unavailable");
                        buffer.clear();
                        if (frameIndex < screenshots.size()) {
                            Bitmap frame = renderFrame(screenshots.get(frameIndex).filePath, width, height);
                            writeYuv420(frame, buffer, semiPlanar);
                            frame.recycle();
                            long ptsUs = frameIndex * 1_000_000L / FPS;
                            encoder.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, 0);
                            frameIndex++;
                        } else {
                            long ptsUs = frameIndex * 1_000_000L / FPS;
                            encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                int outputIndex = encoder.dequeueOutputBuffer(info, 10_000);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IOException("Encoder format changed twice");
                    trackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    continue;
                }
                if (outputIndex < 0) continue;

                ByteBuffer encoded = encoder.getOutputBuffer(outputIndex);
                if (encoded == null) throw new IOException("Encoder output buffer unavailable");
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0) {
                    if (!muxerStarted) throw new IOException("Muxer was not started");
                    encoded.position(info.offset);
                    encoded.limit(info.offset + info.size);
                    muxer.writeSampleData(trackIndex, encoded, info);
                }
                outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(outputIndex, false);
            }
        } catch (IOException error) {
            if (output.exists()) output.delete();
            throw error;
        } catch (RuntimeException error) {
            if (output.exists()) output.delete();
            throw new IOException("Could not encode timelapse: " + error.getMessage(), error);
        } finally {
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (RuntimeException ignored) {
                }
                encoder.release();
            }
            if (muxer != null) {
                try {
                    if (muxerStarted) muxer.stop();
                } catch (RuntimeException ignored) {
                }
                muxer.release();
            }
        }
    }

    File outputFile(TimelineCard card) {
        File dayDir = new File(rootDir(context), card.day == null ? "unknown-day" : card.day);
        String name = String.format(Locale.US, "card_%d_%d_%d.mp4", card.id, card.startMs, card.endMs);
        return new File(dayDir, name);
    }

    static File rootDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "timelapses");
    }

    static long storageBytes(Context context) {
        return sizeOf(rootDir(context));
    }

    static long countTimelapses(Context context) {
        return countMp4(rootDir(context));
    }

    static int purgeToLimit(Context context, long limitBytes) {
        File root = rootDir(context);
        File[] files = listMp4(root);
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long usage = storageBytes(context);
        int deleted = 0;
        for (File file : files) {
            if (usage <= limitBytes) break;
            long length = file.length();
            if (file.delete()) {
                usage -= length;
                deleted++;
            }
        }
        pruneEmptyDirs(root);
        return deleted;
    }

    static int deleteAll(Context context) {
        int count = deleteTree(rootDir(context));
        rootDir(context).mkdirs();
        return count;
    }

    private static List<ScreenshotRecord> existingScreenshots(List<ScreenshotRecord> source) {
        List<ScreenshotRecord> result = new ArrayList<>();
        for (ScreenshotRecord record : source) {
            if (record != null && record.filePath != null && new File(record.filePath).isFile()) {
                result.add(record);
            }
        }
        return result;
    }

    private static int selectColorFormat() throws IOException {
        MediaCodecInfo.CodecCapabilities caps = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (MIME.equalsIgnoreCase(type)) {
                    caps = info.getCapabilitiesForType(type);
                    break;
                }
            }
            if (caps != null) break;
        }
        if (caps == null) throw new IOException("No H.264 encoder available");
        int fallback = -1;
        for (int color : caps.colorFormats) {
            if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return color;
            if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) fallback = color;
            if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible && fallback < 0) fallback = color;
        }
        if (fallback > 0) return fallback;
        throw new IOException("No supported YUV420 encoder color format");
    }

    private static int[] canvasSize(int sourceWidth, int sourceHeight) {
        int width = sourceWidth;
        int height = sourceHeight;
        if (height > MAX_OUTPUT_HEIGHT) {
            float scale = MAX_OUTPUT_HEIGHT / (float) height;
            width = Math.round(width * scale);
            height = MAX_OUTPUT_HEIGHT;
        }
        width = Math.max(2, width - width % 2);
        height = Math.max(2, height - height % 2);
        return new int[]{width, height};
    }

    private static Bitmap renderFrame(String path, int width, int height) throws IOException {
        BitmapFactory.Options bounds = ScreenshotStorage.decodeBounds(path);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Unreadable screenshot");

        Bitmap source = ScreenshotStorage.decodeBitmap(path, Math.max(width, height) * 2);
        if (source == null) throw new IOException("Could not decode screenshot");

        Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float scale = Math.min(width / (float) source.getWidth(), height / (float) source.getHeight());
        float drawWidth = source.getWidth() * scale;
        float drawHeight = source.getHeight() * scale;
        RectF dst = new RectF(
                (width - drawWidth) / 2f,
                (height - drawHeight) / 2f,
                (width + drawWidth) / 2f,
                (height + drawHeight) / 2f);
        canvas.drawBitmap(source, null, dst, paint);
        source.recycle();
        return frame;
    }

    private static void writeYuv420(Bitmap bitmap, ByteBuffer buffer, boolean semiPlanar) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int frameSize = width * height;
        int quarterFrameSize = frameSize / 4;
        byte[] out = new byte[frameSize * 3 / 2];
        int[] pixels = new int[width];

        for (int y = 0; y < height; y++) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int color = pixels[x];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int yy = clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                int u = clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                int v = clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                out[y * width + x] = (byte) yy;
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int chromaIndex = (y / 2) * (width / 2) + (x / 2);
                    if (semiPlanar) {
                        int uv = frameSize + chromaIndex * 2;
                        out[uv] = (byte) u;
                        out[uv + 1] = (byte) v;
                    } else {
                        out[frameSize + chromaIndex] = (byte) u;
                        out[frameSize + quarterFrameSize + chromaIndex] = (byte) v;
                    }
                }
            }
        }
        buffer.put(out);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        long total = 0L;
        File[] children = file.listFiles();
        if (children == null) return 0L;
        for (File child : children) total += sizeOf(child);
        return total;
    }

    private static long countMp4(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.getName().endsWith(".mp4") ? 1L : 0L;
        long total = 0L;
        File[] children = file.listFiles();
        if (children == null) return 0L;
        for (File child : children) total += countMp4(child);
        return total;
    }

    private static File[] listMp4(File root) {
        List<File> files = new ArrayList<>();
        collectMp4(root, files);
        return files.toArray(new File[0]);
    }

    private static void collectMp4(File file, List<File> out) {
        if (file == null || !file.exists()) return;
        if (file.isFile()) {
            if (file.getName().endsWith(".mp4")) out.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) collectMp4(child, out);
    }

    private static void pruneEmptyDirs(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) pruneEmptyDirs(child);
        }
        File[] remaining = dir.listFiles();
        if (remaining != null && remaining.length == 0 && dir.getParentFile() != null) dir.delete();
    }

    private static int deleteTree(File file) {
        if (file == null || !file.exists()) return 0;
        int count = 0;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) count += deleteTree(child);
            }
        } else if (file.getName().endsWith(".mp4")) {
            count++;
        }
        file.delete();
        return count;
    }
}
