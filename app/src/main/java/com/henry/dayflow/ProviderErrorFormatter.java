package com.henry.dayflow;

import java.util.Locale;

final class ProviderErrorFormatter {
    private ProviderErrorFormatter() {}

    static String describe(String providerName, Exception error) {
        String provider = providerName == null ? "" : providerName.trim();
        String lowerProvider = provider.toLowerCase(Locale.US);
        String type = error == null ? "Error" : error.getClass().getSimpleName();
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().trim();
        String lower = message.toLowerCase(Locale.US);

        if (isCustomProvider(lowerProvider)) {
            if (containsAny(lower, "http 401", "http 403", "unauthorized", "forbidden")) {
                return "Custom API rejected the request. Check the API key, endpoint permissions, and model access.";
            }
            if (containsAny(lower, "http 404", "model not found", "not found")) {
                return "Custom API could not find this endpoint or model. Check the base URL and model name.";
            }
            if (containsAny(lower, "http 429", "rate limit", "quota")) {
                return "Custom API rate limit or quota was hit. Retry later or use another key/model.";
            }
            if (containsAny(lower, "http 500", "http 502", "http 503", "http 504", "temporarily unavailable", "暂不可用")) {
                return "Custom API service is unavailable right now. Retry later or switch to a supported model.";
            }
            if (containsAny(lower, "timeout") || type.toLowerCase(Locale.US).contains("timeout")) {
                return "Custom API timed out. Check network/provider load, endpoint, and model availability.";
            }
            if (containsAny(lower, "no choices", "no text", "empty text", "no cards")) {
                return "Custom API answered but did not return usable chat/vision text. Try a vision-capable OpenAI-compatible model.";
            }
            if (containsAny(lower, "not openai-compatible", "cannot be converted to jsonobject", "jsonexception", "response format")) {
                return "Custom API answered, but the response is not OpenAI-compatible chat format. Check that the endpoint is a /v1/chat/completions-compatible URL and the model supports chat/vision.";
            }
            return "Custom API failed: " + shortError(type, message);
        }

        if (lowerProvider.contains("ollama")) {
            if (containsAny(lower, "failed to connect", "connection refused", "timeout")) {
                return "Ollama is not reachable from this phone. Check the host address, port, and network.";
            }
            return "Ollama failed: " + shortError(type, message);
        }

        if (lowerProvider.contains("gemini")) {
            if (containsAny(lower, "http 401", "http 403", "api key", "permission")) {
                return "Gemini rejected the request. Check the API key and model permissions.";
            }
            if (containsAny(lower, "http 404", "model not found", "not found")) {
                return "Gemini could not find this model. Check the model name.";
            }
            return "Gemini failed: " + shortError(type, message);
        }

        return shortError(type, message);
    }

    private static boolean isCustomProvider(String provider) {
        return provider.contains("custom") || provider.contains("openai") || provider.contains("compatible");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private static String shortError(String type, String message) {
        String value = message == null || message.trim().isEmpty() ? type : type + ": " + message.trim();
        return value.length() > 160 ? value.substring(0, 160) + "..." : value;
    }
}
