package com.henry.dayflow;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private DayflowDatabase db;
    private DayflowPrefs prefs;
    private ForegroundAppReader appReader;
    private String selectedTab = "Timeline";
    private String selectedDay;
    private LinearLayout content;
    private LinearLayout tabRow;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        db = new DayflowDatabase(this);
        prefs = new DayflowPrefs(this);
        appReader = new ForegroundAppReader(this);
        selectedDay = TimeUtil.dayKey(System.currentTimeMillis());
        maybeRequestNotifications();
        buildUi();
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            setStatus("Recording started. A full Dayflow card appears after the first 15-minute batch.");
        }
    }

    private void buildUi() {
        GradientFrameLayout root = new GradientFrameLayout(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(16), dp(16), dp(16), dp(12));
        root.addView(shell, new FrameLayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        shell.addView(header, new LinearLayout.LayoutParams(-1, dp(76)));

        DayflowLogoView logo = new DayflowLogoView(this);
        header.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.setPadding(dp(10), 0, 0, 0);
        header.addView(titleStack, new LinearLayout.LayoutParams(0, -1, 1));
        TextView title = text("Dayflow", 34, Colors.TEXT, true);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        titleStack.addView(title);
        statusText = text("", 12, Colors.MUTED, false);
        titleStack.addView(statusText);

        Button start = pillButton("Start");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { requestScreenCapture(); }
        });
        header.addView(start, new LinearLayout.LayoutParams(dp(86), dp(42)));

        Button stop = iconButton("■");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { stopService(new Intent(MainActivity.this, CaptureService.class).setAction(CaptureService.ACTION_STOP)); setStatus("Recording stopped."); }
        });
        header.addView(stop, new LinearLayout.LayoutParams(dp(42), dp(42)));

        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.addView(tabRow);
        shell.addView(tabsScroll, new LinearLayout.LayoutParams(-1, dp(48)));
        for (String tab : new String[]{"Timeline", "Daily", "Weekly", "Chat", "Settings"}) addTab(tab);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(10), 0, dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private void refresh() {
        content.removeAllViews();
        renderTabs();
        List<TimelineCard> dayCards = db.fetchTimelineCards(selectedDay);
        DashboardMetrics metrics = db.dashboardForDay(selectedDay);

        if ("Timeline".equals(selectedTab)) renderTimeline(dayCards, metrics);
        if ("Daily".equals(selectedTab)) renderDaily(dayCards);
        if ("Weekly".equals(selectedTab)) renderWeekly();
        if ("Chat".equals(selectedTab)) renderChat(metrics);
        if ("Settings".equals(selectedTab)) renderSettings();
    }

    private void renderTimeline(List<TimelineCard> cards, DashboardMetrics metrics) {
        LinearLayout actions = row();
        Button previous = smallButton("‹");
        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                selectedDay = TimeUtil.dayKey(TimeUtil.dayStartMs(selectedDay) - TimeUtil.HOUR);
                refresh();
            }
        });
        Button today = smallButton("Today · " + selectedDay);
        today.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { selectedDay = TimeUtil.dayKey(System.currentTimeMillis()); refresh(); }
        });
        Button next = smallButton("›");
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                selectedDay = TimeUtil.dayKey(TimeUtil.dayStartMs(selectedDay) + TimeUtil.DAY + TimeUtil.HOUR);
                refresh();
            }
        });
        actions.addView(previous, new LinearLayout.LayoutParams(dp(44), dp(38)));
        actions.addView(today, new LinearLayout.LayoutParams(0, dp(38), 1));
        actions.addView(next, new LinearLayout.LayoutParams(dp(44), dp(38)));
        content.addView(actions);

        DashboardCanvasView dashboard = new DashboardCanvasView(this);
        dashboard.setMetrics(metrics);
        content.addView(dashboard, new LinearLayout.LayoutParams(-1, dp(530)));
        addGap(14);

        TimelineCanvasView timeline = new TimelineCanvasView(this);
        timeline.setCards(selectedDay, cards);
        content.addView(timeline, new LinearLayout.LayoutParams(-1, dp(24 * 92)));
        addGap(14);
        renderCardList(cards);
    }

    private void renderDaily(List<TimelineCard> cards) {
        DailyWorkflowView workflow = new DailyWorkflowView(this);
        workflow.setCards(selectedDay, cards);
        content.addView(workflow, new LinearLayout.LayoutParams(-1, dp(250)));
        addGap(14);

        LinearLayout standup = panel();
        standup.addView(serif("Standup for today", 26, Colors.ACCENT));
        standup.addView(text(standupText(cards), 14, Colors.TEXT, false));
        Button copy = pillButton("Copy standup update");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { copyText("Dayflow standup", standupText(db.fetchTimelineCards(selectedDay))); }
        });
        standup.addView(copy, new LinearLayout.LayoutParams(-1, dp(44)));
        content.addView(standup);
        addGap(14);
        renderCardList(cards);
    }

    private void renderWeekly() {
        long start = TimeUtil.dayStartMs(TimeUtil.dayKey(System.currentTimeMillis() - 6 * TimeUtil.DAY));
        List<TimelineCard> cards = db.fetchTimelineCardsRange(start, start + 7 * TimeUtil.DAY);
        WeeklyCanvasView weekly = new WeeklyCanvasView(this);
        weekly.setCards(cards);
        content.addView(weekly, new LinearLayout.LayoutParams(-1, dp(520)));
    }

    private void renderChat(DashboardMetrics metrics) {
        LinearLayout panel = panel();
        panel.addView(serif("Chat with your work journal", 28, Colors.TEXT));
        panel.addView(text("Ask about today, distractions, focus blocks, or where the time went.", 14, Colors.MUTED, false));
        final EditText question = new EditText(this);
        question.setSingleLine(false);
        question.setMinLines(2);
        question.setTextColor(Colors.TEXT);
        question.setHintTextColor(Colors.MUTED);
        question.setHint("Where did my time go today?");
        panel.addView(question, new LinearLayout.LayoutParams(-1, dp(94)));

        final TextView answer = text(answerFor("summary", metrics), 14, Colors.TEXT, false);
        Button ask = pillButton("Ask");
        ask.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                answer.setText(answerFor(question.getText().toString(), db.dashboardForDay(selectedDay)));
            }
        });
        panel.addView(ask, new LinearLayout.LayoutParams(-1, dp(44)));
        panel.addView(answer);
        content.addView(panel);
    }

    private void renderSettings() {
        LinearLayout panel = panel();
        panel.addView(serif("Settings", 30, Colors.TEXT));
        panel.addView(text("Permissions", 13, Colors.MUTED, true));

        Button usage = pillButton(appReader.hasUsageAccess() ? "Usage access enabled" : "Open usage access");
        usage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startActivity(appReader.usageAccessIntent()); }
        });
        panel.addView(usage, new LinearLayout.LayoutParams(-1, dp(44)));

        Button analyze = pillButton("Analyze now");
        analyze.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                setStatus("Analyzing recent batches...");
                new Thread(new Runnable() {
                    @Override public void run() {
                        new AnalysisEngine(MainActivity.this).processNow();
                        runOnUiThread(new Runnable() { @Override public void run() { setStatus("Analysis complete."); refresh(); }});
                    }
                }).start();
            }
        });
        panel.addView(analyze, new LinearLayout.LayoutParams(-1, dp(44)));

        Button export = pillButton("Export today as Markdown");
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { copyText("Dayflow export", db.exportMarkdown(selectedDay)); }
        });
        panel.addView(export, new LinearLayout.LayoutParams(-1, dp(44)));

        panel.addView(text("AI provider", 13, Colors.MUTED, true));
        final Switch cloud = new Switch(this);
        cloud.setText("Use Gemini vision when API key is set");
        cloud.setTextColor(Colors.TEXT);
        cloud.setChecked(prefs.useCloudAnalyzer());
        cloud.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setCloudAnalyzer(isChecked);
            }
        });
        panel.addView(cloud);

        final EditText apiKey = new EditText(this);
        apiKey.setHint("Gemini API key");
        apiKey.setSingleLine(true);
        apiKey.setText(prefs.geminiApiKey());
        panel.addView(apiKey, new LinearLayout.LayoutParams(-1, dp(56)));

        final EditText model = new EditText(this);
        model.setHint("Model");
        model.setSingleLine(true);
        model.setText(prefs.geminiModel());
        panel.addView(model, new LinearLayout.LayoutParams(-1, dp(56)));

        Button save = pillButton("Save provider settings");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setGeminiApiKey(apiKey.getText().toString());
                prefs.setGeminiModel(model.getText().toString());
                setStatus("Provider settings saved.");
            }
        });
        panel.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));
        content.addView(panel);
    }

    private void renderCardList(List<TimelineCard> cards) {
        for (TimelineCard card : cards) {
            LinearLayout p = panel();
            p.addView(text(TimeUtil.timeLabel(card.startMs) + " - " + TimeUtil.timeLabel(card.endMs) + " · " + card.category, 12, Colors.MUTED, true));
            p.addView(serif(card.title, 24, Colors.TEXT));
            p.addView(text(card.summary == null ? "" : card.summary, 14, Colors.TEXT, false));
            content.addView(p);
            addGap(10);
        }
    }

    private String standupText(List<TimelineCard> cards) {
        if (cards.isEmpty()) return "No analyzed blocks yet. Start recording and return after one full batch.";
        StringBuilder highlights = new StringBuilder("Yesterday's highlights\n");
        StringBuilder tasks = new StringBuilder("\nToday's tasks\n");
        StringBuilder blockers = new StringBuilder("\nBlockers\n");
        int count = 0;
        for (TimelineCard card : cards) {
            if (!"Distraction".equals(card.category) && !"Idle".equals(card.category) && count < 4) {
                highlights.append("• ").append(card.title).append(" (").append(TimeUtil.shortDuration(card.durationMs())).append(")\n");
                count++;
            }
            if ("Distraction".equals(card.category)) {
                blockers.append("• Drift in ").append(card.title).append("\n");
            }
        }
        tasks.append("• Continue the highest-signal block from today\n");
        return highlights.append(tasks).append(blockers).toString();
    }

    private String answerFor(String question, DashboardMetrics metrics) {
        String lower = question == null ? "" : question.toLowerCase();
        StringBuilder answer = new StringBuilder();
        if (lower.contains("distraction") || lower.contains("distract")) {
            answer.append("Distraction time today: ").append(TimeUtil.shortDuration(metrics.distractionMs)).append(".\n");
        } else if (lower.contains("focus") || lower.contains("productive")) {
            answer.append("Productive share today: ").append(metrics.productivePercent()).append("%.\n");
        } else {
            answer.append("Tracked today: ").append(TimeUtil.shortDuration(metrics.trackedMs))
                    .append(" across ").append(metrics.cardCount).append(" cards.\n");
        }
        answer.append("\nTop categories:\n");
        for (Map.Entry<String, Long> entry : DayflowDatabase.sortedByDuration(metrics.categoryMs)) {
            answer.append("• ").append(entry.getKey()).append(": ").append(TimeUtil.shortDuration(entry.getValue())).append("\n");
        }
        answer.append("\nTop apps:\n");
        int i = 0;
        for (Map.Entry<String, Long> entry : DayflowDatabase.sortedByDuration(metrics.appMs)) {
            if (i++ >= 5) break;
            answer.append("• ").append(entry.getKey()).append(": ").append(TimeUtil.shortDuration(entry.getValue())).append("\n");
        }
        return answer.toString();
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) return;
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    private void maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void addTab(final String tab) {
        Button button = smallButton(tab);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { selectedTab = tab; refresh(); }
        });
        tabRow.addView(button, new LinearLayout.LayoutParams(dp(112), dp(40)));
    }

    private void renderTabs() {
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View child = tabRow.getChildAt(i);
            if (child instanceof Button) {
                Button b = (Button) child;
                b.setTextColor(selectedTab.contentEquals(b.getText()) ? Colors.ACCENT : Colors.TEXT);
            }
        }
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Colors.CARD);
        bg.setStroke(1, Colors.STROKE);
        bg.setCornerRadius(dp(18));
        panel.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        panel.setLayoutParams(lp);
        return panel;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(10));
        return row;
    }

    private TextView text(String value, int sp, int color, boolean caps) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(dp(2), 1.0f);
        v.setTypeface(Typeface.create(Typeface.SANS_SERIF, caps ? Typeface.BOLD : Typeface.NORMAL));
        return v;
    }

    private TextView serif(String value, int sp, int color) {
        TextView v = text(value, sp, color, false);
        v.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        return v;
    }

    private Button pillButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Colors.TEXT);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Colors.ACCENT_SOFT, 0xffe2c2ff});
        bg.setCornerRadius(dp(24));
        button.setBackground(bg);
        return button;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Colors.TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Colors.CARD_ALT);
        bg.setStroke(1, Colors.STROKE);
        bg.setCornerRadius(dp(20));
        button.setBackground(bg);
        return button;
    }

    private Button iconButton(String label) {
        Button button = smallButton(label);
        button.setTextColor(Colors.ACCENT);
        return button;
    }

    private void addGap(int dp) {
        Space s = new Space(this);
        content.addView(s, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private void setStatus(String status) {
        statusText.setText(status);
    }

    private void copyText(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            setStatus("Copied.");
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class FrameLayoutParams extends FrameLayout.LayoutParams {
        FrameLayoutParams(int width, int height) {
            super(width, height);
        }
    }
}
