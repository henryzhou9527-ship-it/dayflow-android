package com.henry.dayflow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

final class LlmJson {
    private LlmJson() {}

    static JSONArray parseCardArray(String text) throws JSONException {
        String clean = stripCodeFence(text);
        JSONArray direct = arrayFromValue(tryParse(clean));
        if (direct != null) return direct;

        String objectSlice = firstJsonSlice(clean, '{', '}');
        JSONArray fromObject = arrayFromValue(tryParse(objectSlice));
        if (fromObject != null) return fromObject;

        String arraySlice = firstJsonSlice(clean, '[', ']');
        JSONArray fromArray = arrayFromValue(tryParse(arraySlice));
        if (fromArray != null) return fromArray;

        throw new JSONException("AI response did not contain a JSON card array");
    }

    private static JSONArray arrayFromValue(Object value) throws JSONException {
        if (value instanceof JSONArray) return (JSONArray) value;
        if (!(value instanceof JSONObject)) return null;

        JSONObject object = (JSONObject) value;
        String[] keys = new String[]{"cards", "timelineCards", "timeline_cards", "activities", "items", "data", "result"};
        for (String key : keys) {
            Object child = object.opt(key);
            if (child instanceof JSONArray) return (JSONArray) child;
            if (child instanceof JSONObject) {
                JSONArray nested = arrayFromValue(child);
                if (nested != null) return nested;
            }
            if (child instanceof String) {
                try {
                    return parseCardArray((String) child);
                } catch (JSONException ignored) {
                }
            }
        }
        return null;
    }

    private static Object tryParse(String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return null;
        try {
            return new JSONTokener(clean).nextValue();
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static String stripCodeFence(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static String firstJsonSlice(String text, char open, char close) {
        String value = text == null ? "" : text;
        int start = value.indexOf(open);
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaping = false;
            for (int i = start; i < value.length(); i++) {
                char c = value.charAt(i);
                if (inString) {
                    if (escaping) {
                        escaping = false;
                    } else if (c == '\\') {
                        escaping = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) return value.substring(start, i + 1);
                }
            }
            start = value.indexOf(open, start + 1);
        }
        return "";
    }
}
