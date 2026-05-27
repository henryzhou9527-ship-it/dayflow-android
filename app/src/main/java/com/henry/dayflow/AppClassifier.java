package com.henry.dayflow;

final class AppClassifier {
    private AppClassifier() {}

    static String categoryFor(String packageName, String label) {
        String haystack = ((packageName == null ? "" : packageName) + " " + (label == null ? "" : label)).toLowerCase();

        if (haystack.trim().isEmpty()) return "Idle";
        if (containsAny(haystack,
                "systemui",
                "keyguard",
                "lockscreen",
                "launcher",
                "trebuchet",
                "one ui home",
                "pixel launcher",
                "always on display",
                "ambient display")) {
            return "Idle";
        }
        if (containsAny(haystack, "slack", "discord", "telegram", "whatsapp", "wechat", "messenger", "messages", "gmail", "mail", "zoom", "meet", "teams")) {
            return "Communication";
        }
        if (containsAny(haystack, "youtube", "tiktok", "instagram", "twitter", ".x", "reddit", "netflix", "hulu", "primevideo", "game", "bilibili", "douyin")) {
            return "Distraction";
        }
        if (containsAny(haystack, "maps", "calendar", "photos", "camera", "wallet", "bank", "shopping", "amazon", "spotify", "music", "health", "fitness")) {
            return "Personal";
        }
        return "Work";
    }

    static String subcategoryFor(String category, String label) {
        if (label == null || label.trim().isEmpty()) return "";
        String normalized = label.trim();
        if ("Work".equals(category)) return "Focused work";
        if ("Communication".equals(category)) return "Messages and meetings";
        if ("Distraction".equals(category)) return "Passive consumption";
        if ("Personal".equals(category)) return "Life admin";
        return normalized;
    }

    static String titleFor(String category, String appLabel) {
        String app = appLabel == null || appLabel.trim().isEmpty() ? "Unknown app" : appLabel.trim();
        if ("Distraction".equals(category)) return app + " drift";
        if ("Communication".equals(category)) return app + " communication";
        if ("Personal".equals(category)) return app + " personal time";
        if ("Idle".equals(category)) return "Idle";
        return "Focused in " + app;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
