package com.henry.dayflow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class ProviderConnectionTester {
    private ProviderConnectionTester() {}

    static String testGemini(String apiKey, String model) throws Exception {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) throw new IllegalStateException("Gemini API key is missing");
        String selectedModel = model == null || model.trim().isEmpty() ? "gemini-2.5-flash" : model.trim();
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject()
                                .put("text", "Reply with exactly: OK")))))
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0)
                        .put("maxOutputTokens", 8));
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + selectedModel + ":generateContent?key=" + key;
        String response = postJson(endpoint, body.toString(), 45_000);
        JSONObject root = new JSONObject(response);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IllegalStateException("Gemini returned no candidates");
        }
        return "Gemini connected · " + selectedModel;
    }

    static String testOllama(String endpoint, String model) throws Exception {
        String base = endpoint == null || endpoint.trim().isEmpty()
                ? "http://127.0.0.1:11434"
                : endpoint.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String selectedModel = model == null || model.trim().isEmpty() ? "qwen3-vl:4b" : model.trim();
        JSONObject body = new JSONObject()
                .put("model", selectedModel)
                .put("prompt", "Reply with exactly: OK")
                .put("stream", false)
                .put("options", new JSONObject()
                        .put("temperature", 0)
                        .put("num_predict", 8));
        String response = postJson(base + "/api/generate", body.toString(), 90_000);
        JSONObject root = new JSONObject(response);
        if (root.has("error")) throw new IllegalStateException(root.optString("error"));
        return "Ollama connected · " + selectedModel;
    }

    private static String postJson(String endpoint, String json, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        OutputStream out = connection.getOutputStream();
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.close();

        InputStream in = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (in != null) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
        }
        String response = buffer.toString("UTF-8");
        if (connection.getResponseCode() >= 400) throw new IllegalStateException(response);
        return response;
    }
}
