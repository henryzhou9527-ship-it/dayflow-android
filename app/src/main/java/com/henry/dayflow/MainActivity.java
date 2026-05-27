package com.henry.dayflow;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        titleStack.addView(title);
        title.setTypeface(DayflowType.serif(this));
        statusText = text("Private timeline ready.", 12, Colors.MUTED, false);
        titleStack.addView(statusText);

        Button start = pillButton("Start");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { requestScreenCapture(); }
        });
        header.addView(start, new LinearLayout.LayoutParams(dp(86), dp(42)));

        Button stop = iconButton("■");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                startService(new Intent(MainActivity.this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
                setStatus("Recording stopped.");
            }
        });
        header.addView(stop, new LinearLayout.LayoutParams(dp(42), dp(42)));

        Button pause = iconButton("Ⅱ");
        pause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                pauseRecording(TimeUtil.MINUTE * 15);
            }
        });
        header.addView(pause, new LinearLayout.LayoutParams(dp(42), dp(42)));

        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.addView(tabRow);
        shell.addView(tabsScroll, new LinearLayout.LayoutParams(-1, dp(48)));
        for (String tab : new String[]{"Timeline", "Daily", "Weekly", "Journal", "Review", "Chat", "Categories", "Settings"}) addTab(tab);

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
        if ("Journal".equals(selectedTab)) renderJournal(dayCards, metrics);
        if ("Review".equals(selectedTab)) renderReview(dayCards);
        if ("Chat".equals(selectedTab)) renderChat(metrics);
        if ("Categories".equals(selectedTab)) renderCategories();
        if ("Settings".equals(selectedTab)) renderSettings();
    }

    private void renderTimeline(List<TimelineCard> cards, DashboardMetrics metrics) {
        if (prefs.isPaused()) {
            LinearLayout paused = panel();
            paused.addView(serif(prefs.pauseLabel(), 24, Colors.TEXT));
            paused.addView(text("Screenshots and analysis are temporarily stopped. Resume from Settings when you are ready.", 14, Colors.MUTED, false));
            content.addView(paused);
        }

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

    private void renderJournal(final List<TimelineCard> cards, final DashboardMetrics metrics) {
        final JournalEntry entry = db.fetchJournal(selectedDay);
        final DayGoal goal = db.fetchDayGoal(selectedDay);

        LinearLayout panel = panel();
        panel.addView(serif("Journal · " + selectedDay, 30, Colors.TEXT));
        panel.addView(text("Plan the day, keep notes, then close the loop with reflection.", 14, Colors.MUTED, false));

        final EditText focusTarget = field("Focus target minutes", String.valueOf(goal.focusTargetMinutes), true);
        final EditText distractionLimit = field("Distraction limit minutes", String.valueOf(goal.distractionLimitMinutes), true);
        panel.addView(focusTarget, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(distractionLimit, new LinearLayout.LayoutParams(-1, dp(54)));

        final Switch skipped = new Switch(this);
        skipped.setText("Skip goals for this day");
        skipped.setTextColor(Colors.TEXT);
        skipped.setTypeface(DayflowType.sans(this));
        skipped.setChecked(goal.skipped);
        panel.addView(skipped);

        final EditText intentions = field("Intentions", entry.intentions, false);
        final EditText goals = field("Goals", entry.goals, false);
        final EditText notes = field("Notes", entry.notes, false);
        final EditText reflections = field("Reflections", entry.reflections, false);
        final EditText summary = field("Summary", entry.summary, false);
        panel.addView(intentions, new LinearLayout.LayoutParams(-1, dp(96)));
        panel.addView(goals, new LinearLayout.LayoutParams(-1, dp(96)));
        panel.addView(notes, new LinearLayout.LayoutParams(-1, dp(120)));
        panel.addView(reflections, new LinearLayout.LayoutParams(-1, dp(120)));
        panel.addView(summary, new LinearLayout.LayoutParams(-1, dp(96)));

        LinearLayout actions = row();
        Button save = pillButton("Save journal");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                DayGoal savedGoal = new DayGoal();
                savedGoal.day = selectedDay;
                savedGoal.focusTargetMinutes = parseInt(focusTarget.getText().toString(), 240);
                savedGoal.distractionLimitMinutes = parseInt(distractionLimit.getText().toString(), 45);
                savedGoal.skipped = skipped.isChecked();
                db.saveDayGoal(savedGoal);

                JournalEntry saved = new JournalEntry();
                saved.day = selectedDay;
                saved.intentions = intentions.getText().toString();
                saved.goals = goals.getText().toString();
                saved.notes = notes.getText().toString();
                saved.reflections = reflections.getText().toString();
                saved.summary = summary.getText().toString();
                saved.status = "saved";
                db.saveJournal(saved);
                setStatus("Journal saved.");
            }
        });
        Button copy = smallButton("Copy");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                copyText("Dayflow journal", journalText(cards, metrics, intentions.getText().toString(), goals.getText().toString(), notes.getText().toString(), reflections.getText().toString()));
            }
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(copy, new LinearLayout.LayoutParams(dp(96), dp(44)));
        panel.addView(actions);
        content.addView(panel);
    }

    private void renderReview(List<TimelineCard> cards) {
        LinearLayout summary = panel();
        summary.addView(serif("Review", 30, Colors.TEXT));
        summary.addView(text("Mark each block as focus, neutral, or distraction to train your day review.", 14, Colors.MUTED, false));
        Map<String, Long> reviewed = db.reviewSummary(selectedDay);
        summary.addView(text(reviewLine("Focus", reviewed) + "\n" + reviewLine("Neutral", reviewed) + "\n" + reviewLine("Distraction", reviewed), 14, Colors.TEXT, false));
        content.addView(summary);

        if (cards.isEmpty()) {
            LinearLayout empty = panel();
            empty.addView(serif("No cards to review", 24, Colors.TEXT));
            empty.addView(text("Analyze a recording batch first, then come back here to rate the day.", 14, Colors.MUTED, false));
            content.addView(empty);
            return;
        }

        for (final TimelineCard card : cards) {
            LinearLayout p = panel();
            p.addView(text(TimeUtil.timeLabel(card.startMs) + " - " + TimeUtil.timeLabel(card.endMs) + " · " + card.category, 12, Colors.MUTED, true));
            p.addView(serif(card.title, 24, Colors.TEXT));
            p.addView(text(card.detailedSummary == null ? card.summary : card.detailedSummary, 14, Colors.TEXT, false));
            addReviewFrames(p, card);
            LinearLayout row = row();
            row.addView(ratingButton(card, "Focus"), new LinearLayout.LayoutParams(0, dp(40), 1));
            row.addView(ratingButton(card, "Neutral"), new LinearLayout.LayoutParams(0, dp(40), 1));
            row.addView(ratingButton(card, "Distraction"), new LinearLayout.LayoutParams(0, dp(40), 1));
            p.addView(row);
            content.addView(p);
        }
    }

    private void renderCategories() {
        LinearLayout intro = panel();
        intro.addView(serif("Categories", 30, Colors.TEXT));
        intro.addView(text("Tune the labels Dayflow uses when it builds timeline cards.", 14, Colors.MUTED, false));
        content.addView(intro);

        for (final Category category : db.fetchCategories()) {
            LinearLayout p = panel();
            p.addView(text(category.system ? "SYSTEM CATEGORY" : "CUSTOM CATEGORY", 12, Colors.MUTED, true));
            final EditText name = field("Name", category.name, true);
            final EditText color = field("Color hex", category.colorHex, true);
            final EditText details = field("Details", category.details, false);
            final EditText order = field("Sort order", String.valueOf(category.order), true);
            final Switch idle = new Switch(this);
            idle.setText("Idle category");
            idle.setTextColor(Colors.TEXT);
            idle.setTypeface(DayflowType.sans(this));
            idle.setChecked(category.idle);
            p.addView(name, new LinearLayout.LayoutParams(-1, dp(54)));
            p.addView(color, new LinearLayout.LayoutParams(-1, dp(54)));
            p.addView(details, new LinearLayout.LayoutParams(-1, dp(86)));
            p.addView(order, new LinearLayout.LayoutParams(-1, dp(54)));
            p.addView(idle);
            LinearLayout actions = row();
            Button save = pillButton("Save");
            save.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    category.name = name.getText().toString().trim();
                    category.colorHex = color.getText().toString().trim();
                    category.details = details.getText().toString();
                    category.order = parseInt(order.getText().toString(), category.order);
                    category.idle = idle.isChecked();
                    db.saveCategory(category);
                    setStatus("Category saved.");
                    refresh();
                }
            });
            actions.addView(save, new LinearLayout.LayoutParams(0, dp(40), 1));
            if (!category.system) {
                Button delete = smallButton("Delete");
                delete.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        db.deleteCategory(category.id);
                        setStatus("Category deleted.");
                        refresh();
                    }
                });
                actions.addView(delete, new LinearLayout.LayoutParams(dp(104), dp(40)));
            }
            p.addView(actions);
            content.addView(p);
        }

        LinearLayout add = panel();
        add.addView(serif("New category", 24, Colors.TEXT));
        final EditText newName = field("Name", "", true);
        final EditText newColor = field("Color hex", "#B984FF", true);
        final EditText newDetails = field("Details", "", false);
        add.addView(newName, new LinearLayout.LayoutParams(-1, dp(54)));
        add.addView(newColor, new LinearLayout.LayoutParams(-1, dp(54)));
        add.addView(newDetails, new LinearLayout.LayoutParams(-1, dp(86)));
        Button create = pillButton("Add category");
        create.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Category category = new Category();
                category.name = newName.getText().toString().trim();
                category.colorHex = newColor.getText().toString().trim();
                category.details = newDetails.getText().toString();
                category.order = db.fetchCategories().size() + 1;
                if (!category.name.isEmpty()) db.saveCategory(category);
                setStatus("Category added.");
                refresh();
            }
        });
        add.addView(create, new LinearLayout.LayoutParams(-1, dp(44)));
        content.addView(add);
    }

    private void renderChat(DashboardMetrics metrics) {
        LinearLayout panel = panel();
        panel.addView(serif("Chat with your work journal", 28, Colors.TEXT));
        panel.addView(text("Ask about today, distractions, focus blocks, or where the time went. Dayflow will use your selected provider when available.", 14, Colors.MUTED, false));
        final EditText question = field("Where did my time go today?", "", false);
        panel.addView(question, new LinearLayout.LayoutParams(-1, dp(94)));

        LinearLayout actions = row();
        Button ask = pillButton("Ask");
        ask.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                final String value = question.getText().toString().trim();
                if (value.isEmpty()) return;
                final String chatDay = selectedDay;
                db.saveChatMessage("user", value);
                setStatus("Thinking...");
                refresh();
                new Thread(new Runnable() {
                    @Override public void run() {
                        final String answer = new ChatResponder(MainActivity.this).answer(chatDay, value);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                db.saveChatMessage("assistant", answer);
                                setStatus("Chat answered.");
                                refresh();
                            }
                        });
                    }
                }, "dayflow-chat").start();
            }
        });
        Button clear = smallButton("Clear");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                db.clearChatMessages();
                setStatus("Chat cleared.");
                refresh();
            }
        });
        actions.addView(ask, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(clear, new LinearLayout.LayoutParams(dp(92), dp(44)));
        panel.addView(actions);
        content.addView(panel);

        List<DayflowChatMessage> messages = db.fetchChatMessages(40);
        if (messages.isEmpty()) {
            LinearLayout welcome = panel();
            welcome.addView(serif("Try asking", 24, Colors.TEXT));
            welcome.addView(text("What should I mention in standup?\nWhere did my focused time go?\nWhat distracted me most today?", 14, Colors.TEXT, false));
            content.addView(welcome);
            return;
        }
        for (DayflowChatMessage message : messages) content.addView(chatMessageView(message));
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

        panel.addView(text("Pause recording", 13, Colors.MUTED, true));
        panel.addView(text(prefs.pauseLabel(), 14, Colors.TEXT, false));
        LinearLayout pauseShort = row();
        Button pause15 = smallButton("15m");
        pause15.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { pauseRecording(15 * TimeUtil.MINUTE); }
        });
        Button pause30 = smallButton("30m");
        pause30.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { pauseRecording(30 * TimeUtil.MINUTE); }
        });
        Button pause60 = smallButton("1h");
        pause60.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { pauseRecording(TimeUtil.HOUR); }
        });
        pauseShort.addView(pause15, new LinearLayout.LayoutParams(0, dp(40), 1));
        pauseShort.addView(pause30, new LinearLayout.LayoutParams(0, dp(40), 1));
        pauseShort.addView(pause60, new LinearLayout.LayoutParams(0, dp(40), 1));
        panel.addView(pauseShort);
        LinearLayout pauseLong = row();
        Button indefinite = smallButton("Indefinite");
        indefinite.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.pauseIndefinitely();
                setStatus("Recording paused indefinitely.");
                refresh();
            }
        });
        Button resume = pillButton("Resume recording");
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.resumeRecording();
                setStatus("Recording resumed.");
                refresh();
            }
        });
        pauseLong.addView(indefinite, new LinearLayout.LayoutParams(dp(118), dp(40)));
        pauseLong.addView(resume, new LinearLayout.LayoutParams(0, dp(40), 1));
        panel.addView(pauseLong);

        Button reprocess = pillButton("Reprocess selected day");
        reprocess.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                db.deleteTimelineDay(selectedDay);
                setStatus("Reprocessing day...");
                new Thread(new Runnable() {
                    @Override public void run() {
                        new AnalysisEngine(MainActivity.this).processNow();
                        runOnUiThread(new Runnable() { @Override public void run() { setStatus("Day reprocessed."); refresh(); }});
                    }
                }).start();
            }
        });
        panel.addView(reprocess, new LinearLayout.LayoutParams(-1, dp(44)));

        Button export = pillButton("Export today as Markdown");
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { copyText("Dayflow export", db.exportMarkdown(selectedDay)); }
        });
        panel.addView(export, new LinearLayout.LayoutParams(-1, dp(44)));

        Button deleteDay = smallButton("Delete selected day cards");
        deleteDay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delete " + selectedDay + "?")
                        .setMessage("Timeline cards for this day will be hidden. Raw screenshots follow the retention setting.")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                db.deleteTimelineDay(selectedDay);
                                setStatus("Day deleted.");
                                refresh();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        panel.addView(deleteDay, new LinearLayout.LayoutParams(-1, dp(44)));

        panel.addView(text("AI provider", 13, Colors.MUTED, true));
        final EditText provider = field("Provider: Heuristic, Gemini, or Ollama", prefs.provider(), true);
        panel.addView(provider, new LinearLayout.LayoutParams(-1, dp(54)));

        final EditText backupProvider = field("Backup provider", prefs.backupProvider(), true);
        panel.addView(backupProvider, new LinearLayout.LayoutParams(-1, dp(54)));

        final Switch cloud = new Switch(this);
        cloud.setText("Use Gemini vision when API key is set");
        cloud.setTextColor(Colors.TEXT);
        cloud.setTypeface(DayflowType.sans(this));
        cloud.setChecked(prefs.useCloudAnalyzer());
        cloud.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setCloudAnalyzer(isChecked);
                if (isChecked) provider.setText("Gemini");
            }
        });
        panel.addView(cloud);

        final EditText apiKey = field("Gemini API key", prefs.geminiApiKey(), true);
        apiKey.setText(prefs.geminiApiKey());
        panel.addView(apiKey, new LinearLayout.LayoutParams(-1, dp(56)));

        final EditText model = field("Model", prefs.geminiModel(), true);
        model.setText(prefs.geminiModel());
        panel.addView(model, new LinearLayout.LayoutParams(-1, dp(56)));

        final EditText ollama = field("Ollama endpoint", prefs.ollamaEndpoint(), true);
        panel.addView(ollama, new LinearLayout.LayoutParams(-1, dp(56)));

        final EditText ollamaModel = field("Ollama vision model", prefs.ollamaModel(), true);
        panel.addView(ollamaModel, new LinearLayout.LayoutParams(-1, dp(56)));

        Button useOllama = smallButton("Use Ollama local AI");
        useOllama.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                provider.setText("Ollama");
                prefs.setProvider("Ollama");
                prefs.setOllamaEndpoint(ollama.getText().toString());
                prefs.setOllamaModel(ollamaModel.getText().toString());
                setStatus("Ollama selected. Analyze now when the local model is running.");
            }
        });
        panel.addView(useOllama, new LinearLayout.LayoutParams(-1, dp(42)));

        Button save = pillButton("Save provider settings");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setProvider(provider.getText().toString());
                prefs.setBackupProvider(backupProvider.getText().toString());
                prefs.setGeminiApiKey(apiKey.getText().toString());
                prefs.setGeminiModel(model.getText().toString());
                prefs.setOllamaEndpoint(ollama.getText().toString());
                prefs.setOllamaModel(ollamaModel.getText().toString());
                setStatus("Provider settings saved.");
            }
        });
        panel.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));

        panel.addView(text("Storage", 13, Colors.MUTED, true));
        StorageStats stats = db.storageStats();
        panel.addView(text(stats.screenshotCount + " screenshots · " + bytes(stats.screenshotBytes) + " · " + stats.cardCount + " cards · " + stats.batchCount + " batches", 14, Colors.TEXT, false));
        final EditText retention = field("Screenshot retention days", String.valueOf(prefs.retentionDays()), true);
        retention.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(retention, new LinearLayout.LayoutParams(-1, dp(54)));
        Button purge = pillButton("Save retention and purge old screenshots");
        purge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                int days = parseInt(retention.getText().toString(), prefs.retentionDays());
                prefs.setRetentionDays(days);
                int count = db.purgeScreenshotsOlderThan(System.currentTimeMillis() - days * TimeUtil.DAY);
                setStatus("Purged " + count + " screenshots.");
                refresh();
            }
        });
        panel.addView(purge, new LinearLayout.LayoutParams(-1, dp(44)));

        panel.addView(text("Privacy blocklist", 13, Colors.MUTED, true));
        ForegroundAppReader.AppSnapshot current = appReader.currentApp();
        if (current.packageName != null) {
            panel.addView(blockedSwitch(current));
        }
        for (ForegroundAppReader.AppSnapshot app : db.recentApps()) {
            if (current.packageName != null && current.packageName.equals(app.packageName)) continue;
            panel.addView(blockedSwitch(app));
        }
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

    private LinearLayout chatMessageView(DayflowChatMessage message) {
        LinearLayout p = panel();
        boolean user = "user".equals(message.role);
        p.addView(text(user ? "YOU" : "DAYFLOW", 12, user ? Colors.ACCENT : Colors.MUTED, true));
        p.addView(text(message.content, 14, Colors.TEXT, false));
        return p;
    }

    private Button ratingButton(final TimelineCard card, final String rating) {
        Button button = smallButton(rating);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                db.saveReviewRating(card, rating);
                setStatus("Marked " + rating.toLowerCase(Locale.US) + ".");
                refresh();
            }
        });
        return button;
    }

    private void addReviewFrames(LinearLayout panel, TimelineCard card) {
        List<ScreenshotRecord> frames = db.screenshotsInRange(
                card.startMs - TimeUtil.MINUTE,
                card.endMs + TimeUtil.MINUTE,
                4);
        if (frames.isEmpty()) {
            panel.addView(text("No screenshots saved for this card.", 12, Colors.MUTED, false));
            return;
        }

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(strip, new HorizontalScrollView.LayoutParams(-2, dp(118)));

        for (ScreenshotRecord frame : frames) {
            Bitmap bitmap = previewBitmap(frame.filePath);
            if (bitmap == null) continue;
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Colors.CARD_ALT);
            image.setContentDescription(frame.appLabel == null ? "Dayflow screenshot" : frame.appLabel + " screenshot");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(148), dp(96));
            lp.setMargins(0, 0, dp(8), 0);
            strip.addView(image, lp);
        }

        if (strip.getChildCount() > 0) panel.addView(scroll);
    }

    private Bitmap previewBitmap(String filePath) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filePath, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        opts.inSampleSize = Math.max(1, longest / 420);
        return BitmapFactory.decodeFile(filePath, opts);
    }

    private String reviewLine(String rating, Map<String, Long> reviewed) {
        Long value = reviewed.get(rating);
        return rating + ": " + TimeUtil.shortDuration(value == null ? 0 : value);
    }

    private String journalText(List<TimelineCard> cards, DashboardMetrics metrics, String intentions, String goals, String notes, String reflections) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Dayflow Journal · ").append(selectedDay).append("\n\n");
        sb.append("Tracked: ").append(TimeUtil.shortDuration(metrics.trackedMs))
                .append(" · Productive: ").append(metrics.productivePercent()).append("%")
                .append(" · Distraction: ").append(TimeUtil.shortDuration(metrics.distractionMs)).append("\n\n");
        sb.append("## Intentions\n").append(emptyDash(intentions)).append("\n\n");
        sb.append("## Goals\n").append(emptyDash(goals)).append("\n\n");
        sb.append("## Notes\n").append(emptyDash(notes)).append("\n\n");
        sb.append("## Reflections\n").append(emptyDash(reflections)).append("\n\n");
        sb.append("## Timeline\n");
        int count = 0;
        for (TimelineCard card : cards) {
            if (count++ >= 10) break;
            sb.append("- ").append(TimeUtil.timeLabel(card.startMs)).append(" · ")
                    .append(card.category).append(" · ").append(card.title).append("\n");
        }
        return sb.toString();
    }

    private EditText field(String hint, String value, boolean singleLine) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setText(value == null ? "" : value);
        edit.setSingleLine(singleLine);
        edit.setMinLines(singleLine ? 1 : 3);
        edit.setGravity(singleLine ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        edit.setTextColor(Colors.TEXT);
        edit.setHintTextColor(Colors.MUTED);
        edit.setTextSize(14);
        edit.setTypeface(DayflowType.sans(this));
        edit.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Colors.CARD_ALT);
        bg.setStroke(1, Colors.STROKE);
        bg.setCornerRadius(dp(12));
        edit.setBackground(bg);
        return edit;
    }

    private Switch blockedSwitch(final ForegroundAppReader.AppSnapshot app) {
        Switch toggle = new Switch(this);
        String label = app.label == null ? app.packageName : app.label;
        toggle.setText(label + "\n" + app.packageName);
        toggle.setTextColor(Colors.TEXT);
        toggle.setTextSize(13);
        toggle.setTypeface(DayflowType.sans(this));
        toggle.setChecked(db.isBlockedApp(app.packageName));
        toggle.setPadding(0, dp(4), 0, dp(4));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                db.setBlockedApp(app.packageName, app.label, isChecked);
                setStatus(isChecked ? "Privacy block enabled." : "Privacy block disabled.");
            }
        });
        return toggle;
    }

    private static String emptyDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String bytes(long value) {
        if (value >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", value / (1024f * 1024f));
        if (value >= 1024L) return String.format(Locale.US, "%.1f KB", value / 1024f);
        return value + " B";
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

    private void pauseRecording(long durationMs) {
        prefs.pauseFor(durationMs);
        setStatus("Recording paused for " + TimeUtil.shortDuration(durationMs) + ".");
        refresh();
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
        v.setTypeface(DayflowType.sans(this, caps));
        return v;
    }

    private TextView serif(String value, int sp, int color) {
        TextView v = text(value, sp, color, false);
        v.setTypeface(DayflowType.serif(this));
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
