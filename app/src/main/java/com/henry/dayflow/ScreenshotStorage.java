package com.henry.dayflow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ScreenshotStorage {
    private static final String KEY_ALIAS = "dayflow_screenshot_storage";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] MAGIC = new byte[]{'D', 'F', 'S', '1', 0x0A};

    private ScreenshotStorage() {}

    static void writeEncryptedJpeg(Bitmap bitmap, File file, int quality) throws Exception {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)) {
            throw new IOException("Could not encode screenshot JPEG");
        }
        byte[] plain = jpeg.toByteArray();

        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, screenshotKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length == 0) throw new IOException("Could not create screenshot encryption IV");
        byte[] encrypted = cipher.doFinal(plain);

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create screenshot folder");
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(MAGIC);
            out.write(iv.length);
            out.write(iv);
            out.write(encrypted);
        } finally {
            out.close();
        }
    }

    static byte[] readJpegBytes(File file, int maxBytes) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Screenshot file is missing");
        if (isEncrypted(file)) {
            try {
                byte[] all = readAll(file);
                int ivLength = all[MAGIC.length] & 0xff;
                int cipherOffset = MAGIC.length + 1 + ivLength;
                if (ivLength <= 0 || cipherOffset >= all.length) throw new IOException("Encrypted screenshot header is invalid");
                byte[] iv = new byte[ivLength];
                System.arraycopy(all, MAGIC.length + 1, iv, 0, ivLength);
                byte[] encrypted = new byte[all.length - cipherOffset];
                System.arraycopy(all, cipherOffset, encrypted, 0, encrypted.length);

                Cipher cipher = Cipher.getInstance(CIPHER);
                cipher.init(Cipher.DECRYPT_MODE, screenshotKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] plain = cipher.doFinal(encrypted);
                return truncate(plain, maxBytes);
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("Could not decrypt screenshot", error);
            }
        }
        return readPlain(file, maxBytes);
    }

    static BitmapFactory.Options decodeBounds(String path) throws IOException {
        File file = new File(path);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (isEncrypted(file)) {
            byte[] data = readJpegBytes(file, Integer.MAX_VALUE);
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        } else {
            BitmapFactory.decodeFile(path, bounds);
        }
        return bounds;
    }

    static Bitmap decodeBitmap(String path, int targetLongest) throws IOException {
        File file = new File(path);
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = decodeBounds(path);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        if (targetLongest > 0) {
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            opts.inSampleSize = Math.max(1, longest / targetLongest);
        }
        if (isEncrypted(file)) {
            byte[] data = readJpegBytes(file, Integer.MAX_VALUE);
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        }
        return BitmapFactory.decodeFile(path, opts);
    }

    static boolean isEncrypted(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < MAGIC.length + 1L) return false;
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] header = new byte[MAGIC.length];
            int read = in.read(header);
            if (read != MAGIC.length) return false;
            for (int i = 0; i < MAGIC.length; i++) {
                if (header[i] != MAGIC[i]) return false;
            }
            return true;
        } finally {
            in.close();
        }
    }

    private static SecretKey screenshotKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static byte[] readPlain(File file, int maxBytes) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int limit = maxBytes <= 0 ? Integer.MAX_VALUE : maxBytes;
            int read;
            while ((read = in.read(buffer)) != -1 && out.size() < limit) {
                out.write(buffer, 0, Math.min(read, limit - out.size()));
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static byte[] truncate(byte[] data, int maxBytes) {
        if (maxBytes <= 0 || data.length <= maxBytes) return data;
        byte[] result = new byte[maxBytes];
        System.arraycopy(data, 0, result, 0, maxBytes);
        return result;
    }
}
