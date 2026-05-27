package com.henry.dayflow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WeeklyNarrativeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Highlight> highlights = new ArrayList<>();
    private final List<Suggestion> topLevel = new ArrayList<>();
    private final List<Suggestion> nextSteps = new ArrayList<>();
    private List<TimelineCard> cards = new ArrayList<>();

    WeeklyNarrativeView(Context context) {
        super(context);
        setMinimumHeight(dp(780));
        setWillNotDraw(false);
    }

    void setCards(List<TimelineCard> cards) {
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        rebuild();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float topHeight = dp(250);
        drawHighlights(canvas, new RectF(0, 0, getWidth(), topHeight));
        drawSuggestions(canvas, new RectF(0, topHeight + dp(14), getWidth(), getHeight()));
    }

    private void rebuild() {
        highlights.clear();
        topLevel.clear();
        nextSteps.clear();

        List<TimelineCard> visible = visibleCards();
        Collections.sort(visible, new Comparator<TimelineCard>() {
            @Override public int compare(TimelineCard a, TimelineCard b) {
                return Long.compare(b.durationMs(), a.durationMs());
            }
        });
        for (int i = 0; i < Math.min(3, visible.size()); i++) {
            TimelineCard card = visible.get(i);
            highlights.add(new Highlight(shortTag(card.category), cardNarrative(card, 170)));
            nextSteps.add(new Suggestion(clean(card.category, "Work"), "Pick up from " + shortText(clean(card.title, "this block"), 42) + ": " + cardNarrative(card, 120)));
        }

        Map<String, CategoryGroup> groups = new LinkedHashMap<>();
        for (TimelineCard card : visible) {
            String category = clean(card.category, "Work");
            String key = category.toLowerCase(Locale.US);
            CategoryGroup group = groups.get(key);
            if (group == null) {
                group = new CategoryGroup(category);
                groups.put(key, group);
            }
            group.durationMs += card.durationMs();
            group.count++;
            if (group.representative == null || card.durationMs() > group.representative.durationMs()) {
                group.representative = card;
            }
        }
        List<CategoryGroup> sortedGroups = new ArrayList<>(groups.values());
        Collections.sort(sortedGroups, new Comparator<CategoryGroup>() {
            @Override public int compare(CategoryGroup a, CategoryGroup b) {
                return Long.compare(b.durationMs, a.durationMs);
            }
        });
        for (int i = 0; i < Math.min(4, sortedGroups.size()); i++) {
            CategoryGroup group = sortedGroups.get(i);
            String detail = TimeUtil.shortDuration(group.durationMs) + " across " + group.count
                    + (group.count == 1 ? " card" : " cards");
            if (group.representative != null) {
                detail += ": " + cardNarrative(group.representative, 110);
            }
            topLevel.add(new Suggestion(group.name, detail));
        }
    }

    private List<TimelineCard> visibleCards() {
        List<TimelineCard> visible = new ArrayList<>();
        for (TimelineCard card : cards) {
            if (isSystem(card) || isIdle(card) || card.durationMs() <= 0) continue;
            visible.add(card);
        }
        return visible;
    }

    private void drawHighlights(Canvas canvas, RectF rect) {
        drawPanel(canvas, rect);
        drawSerif(canvas, "Top Highlights", rect.left + dp(18), rect.top + dp(34), dp(21), Colors.ACCENT);
        if (highlights.isEmpty()) {
            drawSans(canvas, "No highlights yet", rect.left + dp(18), rect.top + dp(76), dp(14), Colors.MUTED);
            return;
        }
        float y = rect.top + dp(72);
        for (Highlight highlight : highlights) {
            drawTag(canvas, highlight.tag, rect.left + dp(18), y - dp(13), dp(84));
            drawWrapped(canvas, highlight.text, rect.left + dp(116), y, rect.width() - dp(134), dp(12), Colors.TEXT, 3);
            y += dp(56);
        }
    }

    private void drawSuggestions(Canvas canvas, RectF rect) {
        drawPanel(canvas, rect);
        drawSerif(canvas, "1:1 suggestions", rect.left + dp(18), rect.top + dp(38), dp(24), Colors.ACCENT);
        boolean twoColumns = getWidth() >= dp(560);
        if (twoColumns) {
            float colW = (rect.width() - dp(62)) / 2f;
            drawSuggestionColumn(canvas, "Top level updates", topLevel, rect.left + dp(26), rect.top + dp(76), colW);
            drawSuggestionColumn(canvas, "Next steps", nextSteps, rect.left + dp(44) + colW, rect.top + dp(76), colW);
        } else {
            drawSuggestionColumn(canvas, "Top level updates", topLevel, rect.left + dp(22), rect.top + dp(76), rect.width() - dp(44));
            drawSuggestionColumn(canvas, "Next steps", nextSteps, rect.left + dp(22), rect.top + dp(306), rect.width() - dp(44));
        }
    }

    private void drawSuggestionColumn(Canvas canvas, String title, List<Suggestion> items, float x, float y, float width) {
        drawSansBold(canvas, title, x, y, dp(14), Colors.ACCENT);
        if (items.isEmpty()) {
            drawSans(canvas, "No items yet", x, y + dp(32), dp(13), Colors.MUTED);
            return;
        }
        float rowY = y + dp(34);
        for (int i = 0; i < Math.min(4, items.size()); i++) {
            Suggestion item = items.get(i);
            paint.setColor(android.graphics.Color.rgb(255, 143, 100));
            canvas.drawRoundRect(new RectF(x, rowY - dp(10), x + dp(2), rowY + dp(34)), dp(1), dp(1), paint);
            String first = item.label + " - ";
            drawSansBold(canvas, fitText(first, width - dp(18), dp(12), true), x + dp(10), rowY, dp(12), Colors.TEXT);
            float firstWidth = textWidth(first, dp(12), true);
            drawWrapped(canvas, item.detail, x + dp(10) + Math.min(firstWidth, width * 0.45f), rowY, width - dp(18) - Math.min(firstWidth, width * 0.45f), dp(12), Colors.TEXT, 2);
            rowY += dp(56);
        }
    }

    private void drawTag(Canvas canvas, String tag, float x, float y, float width) {
        RectF pill = new RectF(x, y, x + width, y + dp(22));
        paint.setColor(android.graphics.Color.rgb(255, 236, 224));
        canvas.drawRoundRect(pill, dp(4), dp(4), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(android.graphics.Color.WHITE);
        canvas.drawRoundRect(pill, dp(4), dp(4), paint);
        paint.setStyle(Paint.Style.FILL);
        drawSansBold(canvas, fitText(tag, width - dp(12), dp(8), true), x + dp(6), y + dp(14), dp(8), android.graphics.Color.rgb(223, 131, 81));
    }

    private void drawPanel(Canvas canvas, RectF rect) {
        paint.setColor(Colors.CARD);
        canvas.drawRoundRect(rect, dp(16), dp(16), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Colors.STROKE);
        canvas.drawRoundRect(rect, dp(16), dp(16), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawWrapped(Canvas canvas, String text, float x, float baseline, float width, float size, int color, int maxLines) {
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(size);
        paint.setColor(color);
        List<String> lines = wrap(text, width, maxLines);
        for (int i = 0; i < lines.size(); i++) {
            canvas.drawText(lines.get(i), x, baseline + i * dp(15), paint);
        }
    }

    private List<String> wrap(String text, float width, int maxLines) {
        List<String> lines = new ArrayList<>();
        String remaining = clean(text, "");
        while (!remaining.isEmpty() && lines.size() < maxLines) {
            int end = remaining.length();
            while (end > 1 && paint.measureText(remaining.substring(0, end)) > width) end--;
            int space = remaining.lastIndexOf(' ', end);
            if (space > 0 && end < remaining.length()) end = space;
            String line = remaining.substring(0, Math.max(1, end)).trim();
            remaining = remaining.substring(Math.min(remaining.length(), Math.max(1, end))).trim();
            if (lines.size() == maxLines - 1 && !remaining.isEmpty()) {
                line = fitText(line + " " + remaining, width, paint.getTextSize(), false);
                remaining = "";
            }
            lines.add(line);
        }
        return lines;
    }

    private String cardNarrative(TimelineCard card, int maxLength) {
        String source = clean(card.detailedSummary, clean(card.summary, clean(card.title, "Captured activity")));
        return shortText(source, maxLength);
    }

    private static String shortTag(String value) {
        String text = clean(value, "WORK").toUpperCase(Locale.US);
        return shortText(text, 18);
    }

    private static String shortText(String text, int max) {
        String trimmed = clean(text, "");
        if (trimmed.length() <= max) return trimmed;
        return trimmed.substring(0, Math.max(1, max - 3)).trim() + "...";
    }

    private static boolean isSystem(TimelineCard card) {
        return clean(card.category, "").toLowerCase(Locale.US).contains("system");
    }

    private static boolean isIdle(TimelineCard card) {
        return clean(card.category, "").toLowerCase(Locale.US).contains("idle");
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private float textWidth(String text, float size, boolean bold) {
        paint.setTypeface(DayflowType.sans(getContext(), bold));
        paint.setTextSize(size);
        return paint.measureText(text);
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

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void drawSerif(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.serif(getContext()));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText(text, x, y, paint);
    }

    private void drawSans(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.sans(getContext(), false));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText(text, x, y, paint);
    }

    private void drawSansBold(Canvas c, String text, float x, float y, float size, int color) {
        paint.setTypeface(DayflowType.sans(getContext(), true));
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText(text, x, y, paint);
    }

    private static final class Highlight {
        final String tag;
        final String text;
        Highlight(String tag, String text) {
            this.tag = tag;
            this.text = text;
        }
    }

    private static final class Suggestion {
        final String label;
        final String detail;
        Suggestion(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }
    }

    private static final class CategoryGroup {
        final String name;
        long durationMs;
        int count;
        TimelineCard representative;
        CategoryGroup(String name) {
            this.name = name;
        }
    }
}
