package com.henry.dayflow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WeeklySankeyView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Node> categories = new ArrayList<>();
    private final List<Node> apps = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private final Map<String, Segment> sourceSegments = new HashMap<>();
    private final Map<String, Segment> categoryOutSegments = new HashMap<>();
    private final Map<String, Segment> appInSegments = new HashMap<>();
    private List<TimelineCard> cards = new ArrayList<>();
    private long weekStartMs = TimeUtil.weekStartMs(System.currentTimeMillis());
    private String selectedKey;

    WeeklySankeyView(Context context) {
        super(context);
        setMinimumHeight(dp(520));
        setWillNotDraw(false);
    }

    void setCards(long weekStartMs, List<TimelineCard> cards) {
        this.weekStartMs = weekStartMs;
        this.cards = cards == null ? new ArrayList<TimelineCard>() : cards;
        selectedKey = null;
        rebuild();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPanel(canvas, 0, 0, getWidth(), getHeight());
        drawSerif(canvas, "Weekly breakdown", dp(18), dp(38), dp(23), Colors.ACCENT);
        drawSans(canvas, TimeUtil.weekLabel(weekStartMs), dp(18), dp(62), dp(12), Colors.MUTED);

        if (categories.isEmpty() || apps.isEmpty()) {
            drawEmpty(canvas);
            return;
        }

        RectF graph = new RectF(dp(18), dp(90), getWidth() - dp(18), getHeight() - dp(88));
        layoutGraph(graph);
        drawColumnGuides(canvas, graph);
        drawSourceFlows(canvas);
        drawAppFlows(canvas);
        drawNodes(canvas, categories);
        drawNodes(canvas, apps);
        drawSource(canvas);
        drawDetail(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        for (Node node : categories) {
            if (node.hit.contains(event.getX(), event.getY())) {
                selectedKey = node.key;
                invalidate();
                return true;
            }
        }
        for (Node node : apps) {
            if (node.hit.contains(event.getX(), event.getY())) {
                selectedKey = node.key;
                invalidate();
                return true;
            }
        }
        selectedKey = null;
        invalidate();
        return true;
    }

    private void rebuild() {
        categories.clear();
        apps.clear();
        links.clear();
        sourceSegments.clear();
        categoryOutSegments.clear();
        appInSegments.clear();

        Map<String, Bucket> categoryBuckets = new LinkedHashMap<>();
        Map<String, Bucket> appBuckets = new LinkedHashMap<>();
        Map<String, Long> rawLinks = new LinkedHashMap<>();
        for (TimelineCard card : cards) {
            if (ignored(card)) continue;
            long duration = Math.max(0, card.durationMs());
            if (duration <= 0) continue;
            String categoryName = clean(card.category, "Work");
            String appName = clean(appFromMetadata(card.metadata), "Unknown app");
            String categoryKey = normalized(categoryName);
            String appKey = normalized(appName);
            Bucket category = bucket(categoryBuckets, categoryKey, categoryName, Colors.colorForCategory(categoryName));
            Bucket app = bucket(appBuckets, appKey, appName, shadeFor(appName, categoryName));
            category.minutes += Math.max(1, duration / TimeUtil.MINUTE);
            app.minutes += Math.max(1, duration / TimeUtil.MINUTE);
            String linkKey = categoryKey + "|" + appKey;
            Long current = rawLinks.get(linkKey);
            rawLinks.put(linkKey, current == null ? duration : current + duration);
        }

        Map<String, String> categoryVisible = visibleBuckets(categoryBuckets, categories, 6, "Other categories", Colors.IDLE);
        Map<String, String> appVisible = visibleBuckets(appBuckets, apps, 10, "Other apps", Colors.IDLE);
        Map<String, Long> visibleLinks = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : rawLinks.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            if (parts.length != 2) continue;
            String categoryKey = categoryVisible.get(parts[0]);
            String appKey = appVisible.get(parts[1]);
            if (categoryKey == null || appKey == null) continue;
            String key = categoryKey + "|" + appKey;
            Long current = visibleLinks.get(key);
            visibleLinks.put(key, current == null ? entry.getValue() : current + entry.getValue());
        }
        for (Map.Entry<String, Long> entry : visibleLinks.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            if (parts.length != 2) continue;
            links.add(new Link(parts[0], parts[1], Math.max(1, entry.getValue() / TimeUtil.MINUTE)));
        }
        Collections.sort(links, new Comparator<Link>() {
            @Override public int compare(Link a, Link b) {
                if (a.fromKey.equals(b.fromKey)) return Long.compare(b.minutes, a.minutes);
                return a.fromKey.compareTo(b.fromKey);
            }
        });
    }

    private Map<String, String> visibleBuckets(Map<String, Bucket> buckets, List<Node> out, int limit, String otherName, int otherColor) {
        List<Bucket> sorted = new ArrayList<>(buckets.values());
        Collections.sort(sorted, new Comparator<Bucket>() {
            @Override public int compare(Bucket a, Bucket b) {
                return Long.compare(b.minutes, a.minutes);
            }
        });
        Map<String, String> remap = new HashMap<>();
        long otherMinutes = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Bucket bucket = sorted.get(i);
            if (i < limit - 1 || sorted.size() <= limit) {
                out.add(new Node(bucket.key, bucket.name, bucket.minutes, bucket.color));
                remap.put(bucket.key, bucket.key);
            } else {
                otherMinutes += bucket.minutes;
                remap.put(bucket.key, "other");
            }
        }
        if (otherMinutes > 0) {
            out.add(new Node("other", otherName, otherMinutes, otherColor));
        }
        return remap;
    }

    private void layoutGraph(RectF graph) {
        float sourceX = graph.left;
        float categoryX = graph.left + graph.width() * 0.34f;
        float appX = graph.left + graph.width() * 0.74f;
        float barW = dp(12);
        float gap = dp(10);
        long total = totalMinutes(categories);
        RectF sourceBar = new RectF(sourceX, graph.top, sourceX + barW, graph.bottom);
        allocateSourceSegments(sourceBar, total);
        allocateNodes(categories, categoryX, barW, graph.top, graph.height(), totalMinutes(categories), gap);
        allocateNodes(apps, appX, barW, graph.top, graph.height(), totalMinutes(apps), gap);
        allocateLinkSegments();
    }

    private void allocateSourceSegments(RectF sourceBar, long total) {
        float y = sourceBar.top;
        for (Node category : categories) {
            float height = Math.max(dp(6), sourceBar.height() * category.minutes / (float) Math.max(1, total));
            Segment segment = new Segment();
            segment.top = y;
            segment.bottom = Math.min(sourceBar.bottom, y + height);
            sourceSegments.put(category.key, segment);
            y = segment.bottom;
        }
    }

    private void allocateNodes(List<Node> nodes, float x, float barW, float top, float height, long total, float gap) {
        float available = height - Math.max(0, nodes.size() - 1) * gap;
        float y = top;
        for (Node node : nodes) {
            float h = Math.max(dp(16), available * node.minutes / (float) Math.max(1, total));
            node.bar.set(x, y, x + barW, Math.min(top + height, y + h));
            node.hit.set(x - dp(8), node.bar.top - dp(6), x + dp(170), node.bar.bottom + dp(6));
            y = node.bar.bottom + gap;
        }
    }

    private void allocateLinkSegments() {
        Map<String, Float> categoryY = new HashMap<>();
        Map<String, Float> appY = new HashMap<>();
        for (Node category : categories) categoryY.put(category.key, category.bar.top);
        for (Node app : apps) appY.put(app.key, app.bar.top);

        for (Node category : categories) {
            long outgoing = outgoingMinutes(category.key);
            for (Link link : links) {
                if (!category.key.equals(link.fromKey)) continue;
                float y = categoryY.get(category.key);
                float h = Math.max(dp(3), category.bar.height() * link.minutes / (float) Math.max(1, outgoing));
                Segment segment = new Segment();
                segment.top = y;
                segment.bottom = Math.min(category.bar.bottom, y + h);
                categoryOutSegments.put(link.key(), segment);
                categoryY.put(category.key, segment.bottom);
            }
        }

        for (Node app : apps) {
            long incoming = incomingMinutes(app.key);
            for (Link link : links) {
                if (!app.key.equals(link.toKey)) continue;
                float y = appY.get(app.key);
                float h = Math.max(dp(3), app.bar.height() * link.minutes / (float) Math.max(1, incoming));
                Segment segment = new Segment();
                segment.top = y;
                segment.bottom = Math.min(app.bar.bottom, y + h);
                appInSegments.put(link.key(), segment);
                appY.put(app.key, segment.bottom);
            }
        }
    }

    private void drawColumnGuides(Canvas canvas, RectF graph) {
        paint.setColor(ColorUtils.withAlpha(Colors.STROKE, 95));
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(graph.left, graph.top - dp(18), graph.right, graph.top - dp(18), paint);
        drawSans(canvas, "Week", graph.left, graph.top - dp(24), dp(11), Colors.MUTED);
        drawSans(canvas, "Categories", graph.left + graph.width() * 0.34f - dp(2), graph.top - dp(24), dp(11), Colors.MUTED);
        drawSans(canvas, "Apps", graph.left + graph.width() * 0.74f - dp(2), graph.top - dp(24), dp(11), Colors.MUTED);
    }

    private void drawSourceFlows(Canvas canvas) {
        if (categories.isEmpty()) return;
        RectF source = new RectF(getWidth() > 0 ? dp(18) : 0, categories.get(0).bar.top, dp(30), categories.get(categories.size() - 1).bar.bottom);
        paint.setColor(ColorUtils.withAlpha(Colors.ACCENT, 180));
        canvas.drawRoundRect(source, dp(4), dp(4), paint);
        for (Node category : categories) {
            Segment segment = sourceSegments.get(category.key);
            if (segment == null) continue;
            drawFlow(canvas, source.right, segment.top, segment.bottom, category.bar.left, category.bar.top, category.bar.bottom,
                    Colors.ACCENT, category.color, isActive(category.key) ? 62 : 18);
        }
    }

    private void drawAppFlows(Canvas canvas) {
        for (Link link : links) {
            Node category = find(categories, link.fromKey);
            Node app = find(apps, link.toKey);
            Segment from = categoryOutSegments.get(link.key());
            Segment to = appInSegments.get(link.key());
            if (category == null || app == null || from == null || to == null) continue;
            boolean active = selectedKey == null || selectedKey.equals(category.key) || selectedKey.equals(app.key);
            drawFlow(canvas, category.bar.right, from.top, from.bottom, app.bar.left, to.top, to.bottom,
                    category.color, app.color, active ? 96 : 20);
        }
    }

    private void drawFlow(Canvas canvas, float x0, float y0Top, float y0Bottom, float x1, float y1Top, float y1Bottom, int fromColor, int toColor, int alpha) {
        Path path = new Path();
        float tension = (x1 - x0) * 0.42f;
        path.moveTo(x0, y0Top);
        path.cubicTo(x0 + tension, y0Top, x1 - tension, y1Top, x1, y1Top);
        path.lineTo(x1, y1Bottom);
        path.cubicTo(x1 - tension, y1Bottom, x0 + tension, y0Bottom, x0, y0Bottom);
        path.close();
        paint.setColor(ColorUtils.withAlpha(mix(fromColor, toColor), alpha));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
    }

    private void drawNodes(Canvas canvas, List<Node> nodes) {
        for (Node node : nodes) {
            boolean active = isActive(node.key);
            paint.setColor(ColorUtils.withAlpha(node.color, active ? 235 : 92));
            canvas.drawRoundRect(node.bar, dp(3), dp(3), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(active && selectedKey != null ? dp(2) : dp(1));
            paint.setColor(active ? Colors.TEXT : ColorUtils.withAlpha(node.color, 140));
            canvas.drawRoundRect(node.bar, dp(3), dp(3), paint);
            paint.setStyle(Paint.Style.FILL);
            float labelWidth = Math.max(dp(38), getWidth() - node.bar.right - dp(22));
            drawSans(canvas, fitText(node.name, labelWidth, dp(12), true), node.bar.right + dp(8), node.bar.top + dp(12), dp(12), active ? Colors.TEXT : Colors.MUTED);
            drawSans(canvas, TimeUtil.shortDuration(node.minutes * TimeUtil.MINUTE), node.bar.right + dp(8), node.bar.top + dp(27), dp(10), Colors.MUTED);
        }
    }

    private void drawSource(Canvas canvas) {
        if (categories.isEmpty()) return;
        long total = totalMinutes(categories);
        RectF source = new RectF(dp(18), categories.get(0).bar.top, dp(30), categories.get(categories.size() - 1).bar.bottom);
        drawSans(canvas, "Recorded week", source.right + dp(8), source.top + dp(12), dp(12), Colors.TEXT);
        drawSans(canvas, TimeUtil.shortDuration(total * TimeUtil.MINUTE), source.right + dp(8), source.top + dp(27), dp(10), Colors.MUTED);
    }

    private void drawDetail(Canvas canvas) {
        RectF detail = new RectF(dp(14), getHeight() - dp(70), getWidth() - dp(14), getHeight() - dp(14));
        paint.setColor(Colors.CARD_ALT);
        canvas.drawRoundRect(detail, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Colors.STROKE);
        canvas.drawRoundRect(detail, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.FILL);

        String line = detailLine();
        String[] parts = line.split("\\n", 2);
        drawSans(canvas, fitText(parts[0], detail.width() - dp(24), dp(13), true), detail.left + dp(12), detail.top + dp(22), dp(13), Colors.TEXT);
        if (parts.length > 1) {
            drawSans(canvas, fitText(parts[1], detail.width() - dp(24), dp(12), false), detail.left + dp(12), detail.top + dp(44), dp(12), Colors.MUTED);
        }
    }

    private String detailLine() {
        Node selected = find(categories, selectedKey);
        if (selected == null) selected = find(apps, selectedKey);
        if (selected != null) {
            return selected.name + " · " + TimeUtil.shortDuration(selected.minutes * TimeUtil.MINUTE) + "\n" + selectedFlowSummary(selected.key);
        }
        Link top = null;
        for (Link link : links) {
            if (top == null || link.minutes > top.minutes) top = link;
        }
        if (top == null) return "No category-to-app flows yet.";
        Node category = find(categories, top.fromKey);
        Node app = find(apps, top.toKey);
        return "Largest flow: " + nameOf(category, top.fromKey) + " -> " + nameOf(app, top.toKey)
                + "\n" + TimeUtil.shortDuration(top.minutes * TimeUtil.MINUTE);
    }

    private String selectedFlowSummary(String key) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Link link : links) {
            if (!key.equals(link.fromKey) && !key.equals(link.toKey)) continue;
            Node from = find(categories, link.fromKey);
            Node to = find(apps, link.toKey);
            if (from == null || to == null) continue;
            if (count++ > 0) sb.append(" · ");
            sb.append(from.name).append(" -> ").append(to.name).append(" ").append(TimeUtil.shortDuration(link.minutes * TimeUtil.MINUTE));
            if (count >= 2) break;
        }
        return sb.toString();
    }

    private boolean isActive(String key) {
        return selectedKey == null || selectedKey.equals(key) || connectedToSelected(key);
    }

    private boolean connectedToSelected(String key) {
        if (selectedKey == null) return true;
        for (Link link : links) {
            if ((selectedKey.equals(link.fromKey) && key.equals(link.toKey))
                    || (selectedKey.equals(link.toKey) && key.equals(link.fromKey))) {
                return true;
            }
        }
        return false;
    }

    private long outgoingMinutes(String categoryKey) {
        long total = 0;
        for (Link link : links) if (categoryKey.equals(link.fromKey)) total += link.minutes;
        return total;
    }

    private long incomingMinutes(String appKey) {
        long total = 0;
        for (Link link : links) if (appKey.equals(link.toKey)) total += link.minutes;
        return total;
    }

    private static long totalMinutes(List<Node> nodes) {
        long total = 0;
        for (Node node : nodes) total += node.minutes;
        return total;
    }

    private static Node find(List<Node> nodes, String key) {
        if (key == null) return null;
        for (Node node : nodes) if (key.equals(node.key)) return node;
        return null;
    }

    private static String nameOf(Node node, String fallback) {
        return node == null ? fallback : node.name;
    }

    private static Bucket bucket(Map<String, Bucket> map, String key, String name, int color) {
        Bucket bucket = map.get(key);
        if (bucket == null) {
            bucket = new Bucket(key, name, color);
            map.put(key, bucket);
        }
        return bucket;
    }

    private static boolean ignored(TimelineCard card) {
        String category = card.category == null ? "" : card.category.toLowerCase(Locale.US);
        return category.contains("idle") || category.contains("system");
    }

    private static String appFromMetadata(String metadata) {
        if (metadata == null) return null;
        String marker = "app=";
        int start = metadata.indexOf(marker);
        if (start < 0) return null;
        int end = metadata.indexOf(';', start);
        String app = end > start ? metadata.substring(start + marker.length(), end) : metadata.substring(start + marker.length());
        app = app.trim();
        return app.isEmpty() ? null : app;
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String normalized(String value) {
        String text = clean(value, "other").toLowerCase(Locale.US);
        return text.replaceAll("[^a-z0-9]+", "-");
    }

    private static int shadeFor(String appName, String categoryName) {
        int base = Colors.colorForCategory(categoryName);
        int shift = Math.abs((appName == null ? "" : appName).hashCode()) % 38;
        return android.graphics.Color.rgb(
                Math.min(255, android.graphics.Color.red(base) + shift),
                Math.max(0, android.graphics.Color.green(base) - shift / 3),
                Math.min(255, android.graphics.Color.blue(base) + shift / 2));
    }

    private static int mix(int a, int b) {
        return android.graphics.Color.rgb(
                (android.graphics.Color.red(a) + android.graphics.Color.red(b)) / 2,
                (android.graphics.Color.green(a) + android.graphics.Color.green(b)) / 2,
                (android.graphics.Color.blue(a) + android.graphics.Color.blue(b)) / 2);
    }

    private void drawEmpty(Canvas canvas) {
        RectF box = new RectF(dp(18), dp(96), getWidth() - dp(18), dp(178));
        paint.setColor(Colors.CARD_ALT);
        canvas.drawRoundRect(box, dp(12), dp(12), paint);
        drawSans(canvas, "Weekly breakdown fills in after app-labeled cards are analyzed.", box.left + dp(12), box.top + dp(38), dp(13), Colors.MUTED);
    }

    private void drawPanel(Canvas c, float x, float y, float w, float h) {
        paint.setColor(Colors.CARD);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), dp(16), dp(16), paint);
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

    private String fitText(String text, float width, float size, boolean bold) {
        paint.setTypeface(DayflowType.sans(getContext(), bold));
        paint.setTextSize(size);
        if (paint.measureText(text) <= width) return text;
        String suffix = "...";
        int end = text.length();
        while (end > 1 && paint.measureText(text.substring(0, end) + suffix) > width) end--;
        return text.substring(0, Math.max(1, end)) + suffix;
    }

    private static final class Bucket {
        final String key;
        final String name;
        final int color;
        long minutes;
        Bucket(String key, String name, int color) {
            this.key = key;
            this.name = name;
            this.color = color;
        }
    }

    private static final class Node {
        final String key;
        final String name;
        final long minutes;
        final int color;
        final RectF bar = new RectF();
        final RectF hit = new RectF();
        Node(String key, String name, long minutes, int color) {
            this.key = key;
            this.name = name;
            this.minutes = minutes;
            this.color = color;
        }
    }

    private static final class Link {
        final String fromKey;
        final String toKey;
        final long minutes;
        Link(String fromKey, String toKey, long minutes) {
            this.fromKey = fromKey;
            this.toKey = toKey;
            this.minutes = minutes;
        }
        String key() {
            return fromKey + "|" + toKey;
        }
    }

    private static final class Segment {
        float top;
        float bottom;
    }
}
