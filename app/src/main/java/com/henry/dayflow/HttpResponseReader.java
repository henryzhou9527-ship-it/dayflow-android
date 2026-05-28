package com.henry.dayflow;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;

final class HttpResponseReader {
    private HttpResponseReader() {}

    static String readOrThrow(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (in != null) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
        }
        String response = buffer.toString("UTF-8");
        if (code >= 400) throw new IllegalStateException("HTTP " + code + ": " + errorText(response));
        return response;
    }

    private static String errorText(String response) {
        if (response == null || response.trim().isEmpty()) return "empty error response";
        String clean = response.replace('\n', ' ').trim();
        return clean.length() > 240 ? clean.substring(0, 240) + "..." : clean;
    }
}
