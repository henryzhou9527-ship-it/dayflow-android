package com.henry.dayflow;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.media.MediaPlayer;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.VideoView;
import android.graphics.drawable.GradientDrawable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int REQ_EXPORT_MARKDOWN = 1003;

    private DayflowDatabase db;
    private DayflowPrefs prefs;
    private ForegroundAppReader appReader;
    private String selectedTab = "Timeline";
    private String selectedDay;
    private long selectedWeekStartMs;
    private LinearLayout content;
    private LinearLayout tabRow;
    private TextView statusText;
    private String pendingExportMarkdown;
    private String pendingExportLabel;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        db = new DayflowDatabase(this);
        prefs = new DayflowPrefs(this);
        appReader = new ForegroundAppReader(this);
        selectedDay = TimeUtil.dayKey(System.currentTimeMillis());
        selectedWeekStartMs = TimeUtil.weekStartMs(System.currentTimeMillis());
        if (!prefs.didOnboard()) selectedTab = "Onboarding";
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
        if (requestCode == REQ_EXPORT_MARKDOWN) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExportMarkdown != null) {
                writePendingExport(data.getData());
            } else {
                pendingExportMarkdown = null;
                pendingExportLabel = null;
                setStatus("Export canceled.");
            }
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
        List<String> tabs = new ArrayList<>();
        if (!prefs.didOnboard()) tabs.add("Onboarding");
        tabs.add("Timeline");
        tabs.add("Daily");
        tabs.add("Weekly");
        tabs.add("Journal");
        tabs.add("Review");
        tabs.add("Chat");
        tabs.add("Categories");
        tabs.add("Settings");
        for (String tab : tabs) addTab(tab);

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

        if ("Onboarding".equals(selectedTab)) {
            renderOnboarding();
            return;
        }
        if ("Timeline".equals(selectedTab)) renderTimeline(dayCards, metrics);
        if ("Daily".equals(selectedTab)) renderDaily(dayCards);
        if ("Weekly".equals(selectedTab)) renderWeekly();
        if ("Journal".equals(selectedTab)) renderJournal(dayCards, metrics);
        if ("Review".equals(selectedTab)) renderReview(dayCards);
        if ("Chat".equals(selectedTab)) renderChat(metrics);
        if ("Categories".equals(selectedTab)) renderCategories();
        if ("Settings".equals(selectedTab)) renderSettings();
    }

    private void renderOnboarding() {
        int step = prefs.onboardingStep();
        setStatus("Setup " + (step + 1) + " of 8 · " + onboardingTitle(step));
        renderOnboardingProgress(step);
        if (step == 0) renderOnboardingIntro();
        if (step == 1) renderOnboardingRole();
        if (step == 2) renderOnboardingPreferences();
        if (step == 3) renderOnboardingProviderChoice();
        if (step == 4) renderOnboardingProviderSetup();
        if (step == 5) renderOnboardingCategories();
        if (step == 6) renderOnboardingPermissions();
        if (step == 7) renderOnboardingCompletion();
    }

    private void renderOnboardingProgress(int step) {
        LinearLayout panel = panel();
        panel.addView(text("SETUP " + (step + 1) + " OF 8", 12, Colors.MUTED, true));
        panel.addView(serif(onboardingTitle(step), 30, Colors.TEXT));
        panel.addView(text(onboardingSubtitle(step), 14, Colors.MUTED, false));

        LinearLayout bar = row();
        bar.setPadding(0, dp(10), 0, 0);
        for (int i = 0; i < 8; i++) {
            View segment = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(i <= step ? Colors.ACCENT : Colors.STROKE);
            bg.setCornerRadius(dp(3));
            segment.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(6), 1);
            lp.setMargins(0, 0, i == 7 ? 0 : dp(5), 0);
            bar.addView(segment, lp);
        }
        panel.addView(bar);
        content.addView(panel);
    }

    private void renderOnboardingIntro() {
        LinearLayout panel = panel();
        DayflowLogoView logo = new DayflowLogoView(this);
        panel.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));
        panel.addView(serif("Your day, written automatically", 34, Colors.TEXT));
        panel.addView(text("Dayflow turns private screen snapshots into a calm timeline, daily journal, review surface, and chat memory. Everything starts local; you choose whether Gemini or Ollama helps read the screenshots.", 14, Colors.TEXT, false));
        addAssetImage(panel, "images/dayflow_content_area.png", 220);
        Button start = pillButton("Start setup");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { goOnboarding(1); }
        });
        panel.addView(start, new LinearLayout.LayoutParams(-1, dp(46)));
        content.addView(panel);
    }

    private void renderOnboardingRole() {
        LinearLayout panel = panel();
        panel.addView(serif("What should Dayflow optimize for?", 28, Colors.TEXT));
        panel.addView(text("The original Dayflow onboarding uses role selection before categories. This saves the same preference locally so the Android setup can stay personal.", 14, Colors.MUTED, false));
        String[] roles = new String[]{"Builder", "Founder", "Student", "Designer", "Researcher"};
        for (String role : roles) {
            final String value = role;
            Button button = value.equals(prefs.onboardingRole()) ? pillButton(value) : smallButton(value);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    prefs.setOnboardingRole(value);
                    goOnboarding(2);
                }
            });
            panel.addView(button, new LinearLayout.LayoutParams(-1, dp(42)));
        }
        addOnboardingBack(panel, 0);
        content.addView(panel);
    }

    private void renderOnboardingPreferences() {
        LinearLayout panel = panel();
        panel.addView(serif("A quick preference check", 28, Colors.TEXT));
        panel.addView(text("Dayflow asks how you found it and whether you already have a paid AI account before recommending a provider.", 14, Colors.MUTED, false));

        final EditText referral = field("How did you hear about Dayflow?", prefs.onboardingReferral(), false);
        panel.addView(referral, new LinearLayout.LayoutParams(-1, dp(92)));

        final Switch paidAi = new Switch(this);
        paidAi.setText("I already have access to Gemini, ChatGPT, Claude, or a local model");
        paidAi.setTextColor(Colors.TEXT);
        paidAi.setTextSize(13);
        paidAi.setTypeface(DayflowType.sans(this));
        paidAi.setChecked(prefs.onboardingHasPaidAi());
        panel.addView(paidAi);

        LinearLayout actions = row();
        Button back = smallButton("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { goOnboarding(1); }
        });
        Button next = pillButton("Continue");
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setOnboardingReferral(referral.getText().toString());
                prefs.setOnboardingHasPaidAi(paidAi.isChecked());
                goOnboarding(3);
            }
        });
        actions.addView(back, new LinearLayout.LayoutParams(dp(96), dp(44)));
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(actions);
        content.addView(panel);
    }

    private void renderOnboardingProviderChoice() {
        LinearLayout panel = panel();
        panel.addView(serif("Choose the analysis engine", 28, Colors.TEXT));
        panel.addView(text("Heuristic mode works offline from app metadata. Gemini and Ollama can inspect screenshots for richer card titles, summaries, and chat answers.", 14, Colors.MUTED, false));
        panel.addView(text("Current provider: " + prefs.provider() + " · Backup: " + prefs.backupProvider(), 13, Colors.TEXT, false));
        panel.addView(providerChoiceButton("Local heuristic only", "Heuristic", 5), new LinearLayout.LayoutParams(-1, dp(44)));
        panel.addView(providerChoiceButton("Google Gemini vision", "Gemini", 4), new LinearLayout.LayoutParams(-1, dp(44)));
        panel.addView(providerChoiceButton("Ollama local vision", "Ollama", 4), new LinearLayout.LayoutParams(-1, dp(44)));
        addOnboardingBack(panel, 2);
        content.addView(panel);
    }

    private void renderOnboardingProviderSetup() {
        LinearLayout panel = panel();
        panel.addView(serif("Connect the provider", 28, Colors.TEXT));
        panel.addView(text("Save the model details now. You can change these later in Settings, including the backup route Dayflow tries if the primary provider fails.", 14, Colors.MUTED, false));

        final EditText provider = field("Provider: Gemini or Ollama", prefs.provider(), true);
        final EditText backupProvider = field("Backup provider", prefs.backupProvider(), true);
        final EditText apiKey = field("Gemini API key", prefs.geminiApiKey(), true);
        final EditText model = field("Gemini model", prefs.geminiModel(), true);
        final EditText ollama = field("Ollama endpoint", prefs.ollamaEndpoint(), true);
        final EditText ollamaModel = field("Ollama vision model", prefs.ollamaModel(), true);
        panel.addView(provider, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(backupProvider, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(apiKey, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(model, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(ollama, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(ollamaModel, new LinearLayout.LayoutParams(-1, dp(54)));

        LinearLayout quick = row();
        Button gemini = smallButton("Gemini");
        gemini.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { provider.setText("Gemini"); }
        });
        Button local = smallButton("Ollama");
        local.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { provider.setText("Ollama"); }
        });
        Button heuristic = smallButton("Heuristic");
        heuristic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { provider.setText("Heuristic"); }
        });
        quick.addView(gemini, new LinearLayout.LayoutParams(0, dp(40), 1));
        quick.addView(local, new LinearLayout.LayoutParams(0, dp(40), 1));
        quick.addView(heuristic, new LinearLayout.LayoutParams(0, dp(40), 1));
        panel.addView(quick);

        Button test = smallButton("Test selected provider");
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                saveProviderFields(provider, backupProvider, apiKey, model, ollama, ollamaModel);
                testSelectedProvider(provider.getText().toString(), apiKey.getText().toString(), model.getText().toString(), ollama.getText().toString(), ollamaModel.getText().toString());
            }
        });
        panel.addView(test, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout actions = row();
        Button back = smallButton("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { goOnboarding(3); }
        });
        Button save = pillButton("Save and continue");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setProvider(provider.getText().toString());
                prefs.setBackupProvider(backupProvider.getText().toString());
                prefs.setGeminiApiKey(apiKey.getText().toString());
                prefs.setGeminiModel(model.getText().toString());
                prefs.setOllamaEndpoint(ollama.getText().toString());
                prefs.setOllamaModel(ollamaModel.getText().toString());
                goOnboarding(5);
            }
        });
        actions.addView(back, new LinearLayout.LayoutParams(dp(96), dp(44)));
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(actions);
        content.addView(panel);
    }

    private void renderOnboardingCategories() {
        LinearLayout panel = panel();
        panel.addView(serif("Tune your categories", 28, Colors.TEXT));
        panel.addView(text("These labels shape timeline cards, reviews, charts, and chat context. The category editor lets you rename them or change colors just like Dayflow's onboarding color step.", 14, Colors.MUTED, false));
        for (Category category : db.fetchCategories()) {
            LinearLayout item = row();
            TextView swatch = new TextView(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(parseColor(category.colorHex, Colors.colorForCategory(category.name)));
            bg.setCornerRadius(dp(8));
            swatch.setBackground(bg);
            item.addView(swatch, new LinearLayout.LayoutParams(dp(16), dp(28)));
            TextView label = text(category.name + " · " + category.details, 13, Colors.TEXT, false);
            label.setPadding(dp(10), 0, 0, 0);
            item.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            panel.addView(item);
        }

        Button edit = smallButton("Open category editor");
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                selectedTab = "Categories";
                refresh();
            }
        });
        panel.addView(edit, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout actions = row();
        Button back = smallButton("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { goOnboarding(4); }
        });
        Button next = pillButton("Use these categories");
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { goOnboarding(6); }
        });
        actions.addView(back, new LinearLayout.LayoutParams(dp(96), dp(44)));
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(actions);
        content.addView(panel);
    }

    private void renderOnboardingPermissions() {
        LinearLayout panel = panel();
        panel.addView(serif("Enable the two Android permissions", 28, Colors.TEXT));
        panel.addView(text("Usage Access tells Dayflow which app is in front. Screen capture is the Android MediaProjection consent prompt that lets Dayflow save private local frames.", 14, Colors.MUTED, false));
        panel.addView(text(appReader.hasUsageAccess() ? "Usage Access: enabled" : "Usage Access: not enabled yet", 14, appReader.hasUsageAccess() ? Colors.ACCENT : Colors.TEXT, false));

        Button usage = pillButton(appReader.hasUsageAccess() ? "Open Usage Access settings" : "Enable Usage Access");
        usage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startActivity(appReader.usageAccessIntent()); }
        });
        panel.addView(usage, new LinearLayout.LayoutParams(-1, dp(44)));

        Button capture = pillButton("Start screen capture");
        capture.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { requestScreenCapture(); }
        });
        panel.addView(capture, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout actions = row();
        Button back = smallButton("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { goOnboarding(5); }
        });
        Button next = pillButton("Continue");
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { goOnboarding(7); }
        });
        actions.addView(back, new LinearLayout.LayoutParams(dp(96), dp(44)));
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(actions);
        content.addView(panel);
    }

    private void renderOnboardingCompletion() {
        LinearLayout panel = panel();
        panel.addView(serif("Dayflow is ready", 32, Colors.TEXT));
        panel.addView(text("Leave recording on and return after one full 15-minute batch. Your timeline, daily summary, review frames, journal, and chat context will all grow from the same local history.", 14, Colors.TEXT, false));
        panel.addView(text("Role: " + prefs.onboardingRole() + "\nProvider: " + prefs.provider() + "\nBackup: " + prefs.backupProvider(), 14, Colors.MUTED, false));
        Button finish = pillButton("Enter Dayflow");
        finish.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { completeOnboarding(); }
        });
        panel.addView(finish, new LinearLayout.LayoutParams(-1, dp(46)));
        addOnboardingBack(panel, 6);
        content.addView(panel);
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
        DashboardMetrics metrics = metricsFor(cards);
        renderDailyGoals(metrics);
        renderDailyFocusSummary(cards, metrics);
        renderDailyDistractionSummary(cards, metrics);

        DailyWorkflowView workflow = new DailyWorkflowView(this);
        workflow.setCards(selectedDay, cards);
        content.addView(workflow, new LinearLayout.LayoutParams(-1, dp(250)));
        addGap(14);

        renderDailyStandup(cards);
        addGap(14);
        renderCardList(cards);
    }

    private void renderDailyGoals(DashboardMetrics metrics) {
        final DayGoal goal = db.fetchDayGoal(selectedDay);
        LinearLayout panel = panel();
        panel.addView(text("DAY GOALS", 12, Colors.MUTED, true));
        panel.addView(serif("Where do you want to spend your time today?", 26, Colors.TEXT));

        if (goal.skipped) {
            panel.addView(text("Goals are skipped for " + selectedDay + ". Your timeline and summaries still update normally.", 14, Colors.MUTED, false));
        } else {
            long focusTargetMs = goal.focusTargetMinutes * TimeUtil.MINUTE;
            long distractionLimitMs = goal.distractionLimitMinutes * TimeUtil.MINUTE;
            panel.addView(goalLine("Focus target", metrics.productiveMs, focusTargetMs, Colors.WORK, true));
            panel.addView(goalLine("Distraction limit", metrics.distractionMs, distractionLimitMs, Colors.DISTRACTION, false));
            panel.addView(text(goalStatus(metrics, goal), 14, Colors.TEXT, false));
        }

        LinearLayout actions = row();
        Button edit = pillButton("Edit goals");
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                selectedTab = "Journal";
                refresh();
            }
        });
        Button skip = smallButton(goal.skipped ? "Resume" : "Skip");
        skip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                goal.skipped = !goal.skipped;
                db.saveDayGoal(goal);
                setStatus(goal.skipped ? "Goals skipped for today." : "Goals resumed.");
                refresh();
            }
        });
        actions.addView(edit, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(skip, new LinearLayout.LayoutParams(dp(104), dp(44)));
        panel.addView(actions);
        content.addView(panel);
    }

    private void renderDailyFocusSummary(List<TimelineCard> cards, DashboardMetrics metrics) {
        LinearLayout panel = panel();
        panel.addView(text("YOUR FOCUS", 12, Colors.MUTED, true));
        panel.addView(serif("Total focus time", 24, Colors.TEXT));
        panel.addView(serif(TimeUtil.shortDuration(metrics.productiveMs), 36, Colors.ACCENT));
        TimelineCard longest = longestUsefulCard(cards);
        if (longest == null) {
            panel.addView(text("No focus block yet. Once Dayflow sees useful work, the longest block appears here.", 14, Colors.MUTED, false));
        } else {
            panel.addView(text("Longest block: " + longest.title + " · " + TimeUtil.shortDuration(longest.durationMs()), 14, Colors.TEXT, false));
            panel.addView(text(shortText(longest.summary, 150), 13, Colors.MUTED, false));
        }
        content.addView(panel);
    }

    private void renderDailyDistractionSummary(List<TimelineCard> cards, DashboardMetrics metrics) {
        LinearLayout panel = panel();
        panel.addView(text("DISTRACTIONS SO FAR", 12, Colors.MUTED, true));
        int ratio = metrics.trackedMs <= 0 ? 0 : Math.round(metrics.distractionMs * 100f / metrics.trackedMs);
        panel.addView(serif(TimeUtil.shortDuration(metrics.distractionMs) + " distracted", 24, Colors.TEXT));
        panel.addView(text("Captured " + TimeUtil.shortDuration(metrics.trackedMs) + " · " + ratio + "% distraction ratio", 14, Colors.MUTED, false));
        panel.addView(progressBar(Colors.DISTRACTION, ratio / 100f), new LinearLayout.LayoutParams(-1, dp(14)));
        TimelineCard longest = longestDistractionCard(cards);
        if (longest == null) {
            panel.addView(text("No distraction pattern detected yet.", 14, Colors.MUTED, false));
        } else {
            panel.addView(text("Largest drift: " + longest.title + " · " + TimeUtil.shortDuration(longest.durationMs()), 14, Colors.TEXT, false));
            panel.addView(text(shortText(longest.summary, 150), 13, Colors.MUTED, false));
        }
        content.addView(panel);
    }

    private void renderDailyStandup(List<TimelineCard> cards) {
        final DailyStandupEntry saved = db.fetchDailyStandup(selectedDay);
        final String draft = saved == null ? standupText(cards) : saved.content;

        LinearLayout standup = panel();
        standup.addView(text("DAILY STANDUP", 12, Colors.MUTED, true));
        standup.addView(serif("Standup for today", 26, Colors.ACCENT));
        standup.addView(text(saved == null ? "Draft from current timeline." : "Saved " + TimeUtil.timeLabel(saved.updatedAtMs), 13, Colors.MUTED, false));
        standup.addView(text(draft, 14, Colors.TEXT, false));

        LinearLayout actions = row();
        Button copy = pillButton("Copy");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { copyText("Dayflow standup", draft); }
        });
        Button regenerate = smallButton("Regenerate");
        regenerate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { regenerateStandup(selectedDay); }
        });
        Button save = smallButton("Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                db.saveDailyStandup(selectedDay, draft);
                setStatus("Standup saved.");
                refresh();
            }
        });
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(regenerate, new LinearLayout.LayoutParams(dp(126), dp(44)));
        actions.addView(save, new LinearLayout.LayoutParams(dp(82), dp(44)));
        standup.addView(actions);
        content.addView(standup);
    }

    private void regenerateStandup(final String day) {
        setStatus("Regenerating standup...");
        new Thread(new Runnable() {
            @Override public void run() {
                final String answer = new ChatResponder(MainActivity.this).standup(day);
                db.saveDailyStandup(day, answer);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setStatus("Standup regenerated.");
                        refresh();
                    }
                });
            }
        }, "dayflow-standup").start();
    }

    private void renderWeekly() {
        long start = selectedWeekStartMs <= 0 ? TimeUtil.weekStartMs(System.currentTimeMillis()) : selectedWeekStartMs;
        long end = start + 7 * TimeUtil.DAY;
        List<TimelineCard> cards = db.fetchTimelineCardsRange(start, end);
        List<TimelineCard> previousCards = db.fetchTimelineCardsRange(start - 7 * TimeUtil.DAY, start);

        LinearLayout actions = row();
        Button previous = smallButton("‹");
        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                selectedWeekStartMs -= 7 * TimeUtil.DAY;
                refresh();
            }
        });
        Button current = pillButton(TimeUtil.weekLabel(start));
        current.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                selectedWeekStartMs = TimeUtil.weekStartMs(System.currentTimeMillis());
                refresh();
            }
        });
        Button next = smallButton("›");
        next.setEnabled(start + 7 * TimeUtil.DAY < TimeUtil.weekStartMs(System.currentTimeMillis()) + 7 * TimeUtil.DAY);
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                selectedWeekStartMs += 7 * TimeUtil.DAY;
                refresh();
            }
        });
        actions.addView(previous, new LinearLayout.LayoutParams(dp(44), dp(38)));
        actions.addView(current, new LinearLayout.LayoutParams(0, dp(38), 1));
        actions.addView(next, new LinearLayout.LayoutParams(dp(44), dp(38)));
        content.addView(actions);

        WeeklyCanvasView weekly = new WeeklyCanvasView(this);
        weekly.setCards(start, cards);
        content.addView(weekly, new LinearLayout.LayoutParams(-1, dp(520)));
        addGap(14);
        renderWeeklyInsights(start, cards, previousCards);
    }

    private void renderWeeklyInsights(long weekStart, List<TimelineCard> cards, List<TimelineCard> previousCards) {
        DashboardMetrics metrics = metricsFor(cards);
        DashboardMetrics previous = metricsFor(previousCards);

        LinearLayout summary = panel();
        summary.addView(text("WEEKLY SUMMARY", 12, Colors.MUTED, true));
        summary.addView(serif("This week's operating picture", 28, Colors.TEXT));
        if (cards.isEmpty()) {
            summary.addView(text("No analyzed cards in this week yet. Dayflow unlocks the full weekly breakdown after enough timeline history exists.", 14, Colors.MUTED, false));
            content.addView(summary);
            return;
        }

        long delta = metrics.trackedMs - previous.trackedMs;
        String deltaText = (delta >= 0 ? "+" : "-") + TimeUtil.shortDuration(Math.abs(delta)) + " vs previous week";
        TimelineCard longest = longestUsefulCard(cards);
        summary.addView(text(
                "Tracked " + TimeUtil.shortDuration(metrics.trackedMs) +
                        " · " + metrics.productivePercent() + "% productive · " +
                        TimeUtil.shortDuration(metrics.distractionMs) + " distraction\n" +
                        deltaText +
                        "\nLongest useful block: " + (longest == null ? "none yet" : longest.title + " · " + TimeUtil.shortDuration(longest.durationMs())),
                14, Colors.TEXT, false));
        summary.addView(text("Busiest day: " + busiestDayLabel(weekStart, cards), 14, Colors.MUTED, false));
        content.addView(summary);

        LinearLayout suggestions = panel();
        suggestions.addView(text("1:1 SUGGESTIONS", 12, Colors.MUTED, true));
        suggestions.addView(serif("Top level updates", 26, Colors.TEXT));
        int categoryCount = 0;
        for (Map.Entry<String, Long> entry : DayflowDatabase.sortedByDuration(metrics.categoryMs)) {
            if (categoryCount++ >= 4) break;
            suggestions.addView(text("• " + entry.getKey() + ": " + TimeUtil.shortDuration(entry.getValue()) + " across " + countCardsInCategory(cards, entry.getKey()) + " cards.", 14, Colors.TEXT, false));
        }
        suggestions.addView(serif("Next steps", 24, Colors.ACCENT));
        int nextCount = 0;
        for (TimelineCard card : usefulCardsByDuration(cards)) {
            if (nextCount++ >= 3) break;
            suggestions.addView(text("• Pick up from " + card.title + ": " + shortText(card.summary, 120), 14, Colors.TEXT, false));
        }
        if (nextCount == 0) {
            suggestions.addView(text("• Build one focused block this week so Dayflow has a useful thread to resume.", 14, Colors.TEXT, false));
        }
        content.addView(suggestions);

        LinearLayout apps = panel();
        apps.addView(text("APPLICATION INTERACTIONS", 12, Colors.MUTED, true));
        apps.addView(serif("Where the week moved", 26, Colors.TEXT));
        List<Map.Entry<String, Long>> appsByDuration = topEntries(metrics.appMs, 5);
        for (Map.Entry<String, Long> entry : appsByDuration) {
            apps.addView(text("• " + entry.getKey() + ": " + TimeUtil.shortDuration(entry.getValue()), 14, Colors.TEXT, false));
        }
        if (appsByDuration.isEmpty()) {
            apps.addView(text("App labels appear after Usage Access is enabled and new batches are captured.", 14, Colors.MUTED, false));
        }
        Map<String, Long> transitions = appTransitions(cards);
        if (!transitions.isEmpty()) {
            apps.addView(serif("Common switches", 24, Colors.ACCENT));
            for (Map.Entry<String, Long> entry : topEntries(transitions, 4)) {
                apps.addView(text("• " + entry.getKey() + " · " + entry.getValue() + "x", 14, Colors.TEXT, false));
            }
        }
        content.addView(apps);
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
            addReviewScrubber(p, card);
            p.addView(text(card.detailedSummary == null ? card.summary : card.detailedSummary, 14, Colors.TEXT, false));
            addReviewFrames(p, card);
            addTimelapseControls(p, card);
            LinearLayout row = row();
            row.addView(ratingButton(card, "Focus"), new LinearLayout.LayoutParams(0, dp(40), 1));
            row.addView(ratingButton(card, "Neutral"), new LinearLayout.LayoutParams(0, dp(40), 1));
            row.addView(ratingButton(card, "Distraction"), new LinearLayout.LayoutParams(0, dp(40), 1));
            p.addView(row);
            p.addView(cardActions(card));
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
        String section = settingsSection();
        addSettingsSectionSelector(panel, section);
        if ("Account".equals(section)) {
            renderSettingsAccount(panel);
            content.addView(panel);
            return;
        }
        if ("Storage".equals(section)) {
            renderSettingsStorage(panel);
            content.addView(panel);
            return;
        }
        if ("Privacy".equals(section)) {
            renderSettingsPrivacy(panel);
            content.addView(panel);
            return;
        }
        if ("Providers".equals(section)) {
            renderSettingsProviders(panel);
            content.addView(panel);
            return;
        }
        if ("Export".equals(section)) {
            renderSettingsData(panel);
            content.addView(panel);
            return;
        }
        if ("Other".equals(section)) {
            renderSettingsOther(panel);
            content.addView(panel);
            return;
        }
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

        Button setup = smallButton("Run first-run setup again");
        setup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setDidOnboard(false);
                prefs.setOnboardingStep(0);
                selectedTab = "Onboarding";
                buildUi();
                refresh();
            }
        });
        panel.addView(setup, new LinearLayout.LayoutParams(-1, dp(42)));

        addJournalReminderSettings(panel);

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
                confirmReprocessDay(selectedDay);
            }
        });
        panel.addView(reprocess, new LinearLayout.LayoutParams(-1, dp(44)));

        addDataExportSettings(panel);

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
        panel.addView(text(stats.screenshotCount + " screenshots · " + bytes(stats.screenshotBytes) + "\n" +
                stats.timelapseCount + " timelapses · " + bytes(stats.timelapseBytes) + "\n" +
                stats.cardCount + " cards · " + stats.batchCount + " batches", 14, Colors.TEXT, false));
        final EditText retention = field("Screenshot retention days", String.valueOf(prefs.retentionDays()), true);
        retention.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(retention, new LinearLayout.LayoutParams(-1, dp(54)));
        final Switch saveTimelapses = new Switch(this);
        saveTimelapses.setText("Save all timelapses to disk");
        saveTimelapses.setTextColor(Colors.TEXT);
        saveTimelapses.setTextSize(13);
        saveTimelapses.setTypeface(DayflowType.sans(this));
        saveTimelapses.setChecked(prefs.saveAllTimelapsesToDisk());
        panel.addView(saveTimelapses);
        final EditText timelapseLimit = field("Timelapse storage limit MB", String.valueOf(prefs.timelapseLimitMb()), true);
        timelapseLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(timelapseLimit, new LinearLayout.LayoutParams(-1, dp(54)));
        Button purge = pillButton("Save retention and purge old screenshots");
        purge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                int days = parseInt(retention.getText().toString(), prefs.retentionDays());
                prefs.setRetentionDays(days);
                prefs.setSaveAllTimelapsesToDisk(saveTimelapses.isChecked());
                prefs.setTimelapseLimitMb(parseInt(timelapseLimit.getText().toString(), prefs.timelapseLimitMb()));
                int count = db.purgeScreenshotsOlderThan(System.currentTimeMillis() - days * TimeUtil.DAY);
                int videos = TimelapseGenerator.purgeToLimit(MainActivity.this, prefs.timelapseLimitBytes());
                setStatus("Purged " + count + " screenshots and " + videos + " timelapses.");
                refresh();
            }
        });
        panel.addView(purge, new LinearLayout.LayoutParams(-1, dp(44)));

        Button deleteTimelapses = smallButton("Delete generated timelapses");
        deleteTimelapses.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delete saved timelapses?")
                        .setMessage("Timeline card text and screenshots stay intact. Videos can be generated again from saved screenshots.")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                int count = TimelapseGenerator.deleteAll(MainActivity.this);
                                setStatus("Deleted " + count + " timelapses.");
                                refresh();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        panel.addView(deleteTimelapses, new LinearLayout.LayoutParams(-1, dp(44)));

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

    private void addSettingsSectionSelector(LinearLayout panel, String selected) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(12));
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, dp(58)));
        String[] sections = new String[]{"Account", "Storage", "Privacy", "Providers", "Export", "Other"};
        for (String section : sections) {
            Button button = section.equals(selected) ? pillButton(section) : smallButton(section);
            final String target = section;
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    prefs.setSettingsSection(target);
                    refresh();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(40));
            lp.setMargins(0, 0, dp(8), 0);
            row.addView(button, lp);
        }
        panel.addView(scroll);
    }

    private String settingsSection() {
        String section = prefs.settingsSection();
        if ("Account".equals(section) || "Storage".equals(section) || "Privacy".equals(section)
                || "Providers".equals(section) || "Export".equals(section) || "Other".equals(section)) {
            return section;
        }
        return "Account";
    }

    private void renderSettingsAccount(LinearLayout panel) {
        panel.addView(text("Account", 13, Colors.MUTED, true));
        panel.addView(text("Local Android build · " + appVersionLabel(), 14, Colors.TEXT, false));
        panel.addView(text("Role: " + prefs.onboardingRole() + "\nProvider: " + prefs.provider() + "\nBackup: " + prefs.backupProvider(), 14, Colors.MUTED, false));

        Button setup = pillButton("Run first-run setup again");
        setup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setDidOnboard(false);
                prefs.setOnboardingStep(0);
                selectedTab = "Onboarding";
                buildUi();
                refresh();
            }
        });
        panel.addView(setup, new LinearLayout.LayoutParams(-1, dp(44)));

        panel.addView(text("Permissions", 13, Colors.MUTED, true));
        Button usage = pillButton(appReader.hasUsageAccess() ? "Usage access enabled" : "Open usage access");
        usage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startActivity(appReader.usageAccessIntent()); }
        });
        panel.addView(usage, new LinearLayout.LayoutParams(-1, dp(44)));

        Button capture = pillButton("Start screen capture");
        capture.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { requestScreenCapture(); }
        });
        panel.addView(capture, new LinearLayout.LayoutParams(-1, dp(44)));

        Button analyze = smallButton("Analyze now");
        analyze.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                setStatus("Analyzing recent batches...");
                new Thread(new Runnable() {
                    @Override public void run() {
                        new AnalysisEngine(MainActivity.this).processNow();
                        runOnUiThread(new Runnable() { @Override public void run() { setStatus("Analysis complete."); refresh(); }});
                    }
                }, "dayflow-analysis-now").start();
            }
        });
        panel.addView(analyze, new LinearLayout.LayoutParams(-1, dp(42)));
    }

    private void renderSettingsStorage(LinearLayout panel) {
        panel.addView(text("Recording status", 13, Colors.MUTED, true));
        panel.addView(text((appReader.hasUsageAccess() ? "Usage access: granted" : "Usage access: missing") + "\nRecorder: " + (prefs.isPaused() ? prefs.pauseLabel() : "Ready"), 14, Colors.TEXT, false));

        panel.addView(text("Disk usage", 13, Colors.MUTED, true));
        StorageStats stats = db.storageStats();
        panel.addView(text(stats.screenshotCount + " screenshots · " + bytes(stats.screenshotBytes) + "\n" +
                stats.timelapseCount + " timelapses · " + bytes(stats.timelapseBytes) + "\n" +
                stats.cardCount + " cards · " + stats.batchCount + " batches", 14, Colors.TEXT, false));
        final EditText retention = field("Screenshot retention days", String.valueOf(prefs.retentionDays()), true);
        retention.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(retention, new LinearLayout.LayoutParams(-1, dp(54)));
        final Switch saveTimelapses = new Switch(this);
        saveTimelapses.setText("Save all timelapses to disk");
        saveTimelapses.setTextColor(Colors.TEXT);
        saveTimelapses.setTextSize(13);
        saveTimelapses.setTypeface(DayflowType.sans(this));
        saveTimelapses.setChecked(prefs.saveAllTimelapsesToDisk());
        panel.addView(saveTimelapses);
        final EditText timelapseLimit = field("Timelapse storage limit MB", String.valueOf(prefs.timelapseLimitMb()), true);
        timelapseLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(timelapseLimit, new LinearLayout.LayoutParams(-1, dp(54)));

        Button purge = pillButton("Save limits and purge old files");
        purge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                int days = parseInt(retention.getText().toString(), prefs.retentionDays());
                prefs.setRetentionDays(days);
                prefs.setSaveAllTimelapsesToDisk(saveTimelapses.isChecked());
                prefs.setTimelapseLimitMb(parseInt(timelapseLimit.getText().toString(), prefs.timelapseLimitMb()));
                int count = db.purgeScreenshotsOlderThan(System.currentTimeMillis() - days * TimeUtil.DAY);
                int videos = TimelapseGenerator.purgeToLimit(MainActivity.this, prefs.timelapseLimitBytes());
                setStatus("Purged " + count + " screenshots and " + videos + " timelapses.");
                refresh();
            }
        });
        panel.addView(purge, new LinearLayout.LayoutParams(-1, dp(44)));

        Button deleteTimelapses = smallButton("Delete generated timelapses");
        deleteTimelapses.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delete saved timelapses?")
                        .setMessage("Timeline card text and screenshots stay intact. Videos can be generated again from saved screenshots.")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                int count = TimelapseGenerator.deleteAll(MainActivity.this);
                                setStatus("Deleted " + count + " timelapses.");
                                refresh();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        panel.addView(deleteTimelapses, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void renderSettingsPrivacy(LinearLayout panel) {
        panel.addView(text("Recording privacy", 13, Colors.MUTED, true));
        panel.addView(text("Choose apps Dayflow should hide from screenshots. Blocked apps save a private placeholder so timeline continuity is preserved.", 14, Colors.TEXT, false));
        ForegroundAppReader.AppSnapshot current = appReader.currentApp();
        if (current.packageName != null) panel.addView(blockedSwitch(current));
        for (ForegroundAppReader.AppSnapshot app : db.recentApps()) {
            if (current.packageName != null && current.packageName.equals(app.packageName)) continue;
            panel.addView(blockedSwitch(app));
        }
        panel.addView(text(db.blockedAppCount() + " apps blocked", 12, Colors.MUTED, false));
        Button clear = smallButton("Clear blocked apps");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                db.clearBlockedApps();
                setStatus("Privacy blocklist cleared.");
                refresh();
            }
        });
        panel.addView(clear, new LinearLayout.LayoutParams(-1, dp(42)));

        panel.addView(text("Recording cadence", 13, Colors.MUTED, true));
        final EditText screenshotInterval = field("Screenshot interval seconds", String.valueOf(prefs.screenshotIntervalMs() / TimeUtil.SECOND), true);
        final EditText batchMinutes = field("Batch target minutes", String.valueOf(prefs.targetBatchMs() / TimeUtil.MINUTE), true);
        final EditText maxGapMinutes = field("Max gap minutes", String.valueOf(prefs.maxGapMs() / TimeUtil.MINUTE), true);
        final EditText lookbackMinutes = field("Card lookback minutes", String.valueOf(prefs.cardLookbackMs() / TimeUtil.MINUTE), true);
        screenshotInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        batchMinutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxGapMinutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        lookbackMinutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        panel.addView(screenshotInterval, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(batchMinutes, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(maxGapMinutes, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(lookbackMinutes, new LinearLayout.LayoutParams(-1, dp(54)));
        Button save = pillButton("Save recording cadence");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setScreenshotIntervalMs(parseInt(screenshotInterval.getText().toString(), (int) (prefs.screenshotIntervalMs() / TimeUtil.SECOND)) * TimeUtil.SECOND);
                prefs.setTargetBatchMs(parseInt(batchMinutes.getText().toString(), (int) (prefs.targetBatchMs() / TimeUtil.MINUTE)) * TimeUtil.MINUTE);
                prefs.setMaxGapMs(parseInt(maxGapMinutes.getText().toString(), (int) (prefs.maxGapMs() / TimeUtil.MINUTE)) * TimeUtil.MINUTE);
                prefs.setCardLookbackMs(parseInt(lookbackMinutes.getText().toString(), (int) (prefs.cardLookbackMs() / TimeUtil.MINUTE)) * TimeUtil.MINUTE);
                setStatus("Recording cadence saved.");
                refresh();
            }
        });
        panel.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void renderSettingsProviders(LinearLayout panel) {
        panel.addView(text("Providers", 13, Colors.MUTED, true));
        panel.addView(text("Primary: " + prefs.provider() + "\nBackup: " + prefs.backupProvider(), 14, Colors.TEXT, false));
        final EditText provider = field("Provider: Heuristic, Gemini, or Ollama", prefs.provider(), true);
        final EditText backupProvider = field("Backup provider", prefs.backupProvider(), true);
        final EditText apiKey = field("Gemini API key", prefs.geminiApiKey(), true);
        final EditText model = field("Gemini model", prefs.geminiModel(), true);
        final EditText ollama = field("Ollama endpoint", prefs.ollamaEndpoint(), true);
        final EditText ollamaModel = field("Ollama vision model", prefs.ollamaModel(), true);
        panel.addView(provider, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(backupProvider, new LinearLayout.LayoutParams(-1, dp(54)));
        panel.addView(apiKey, new LinearLayout.LayoutParams(-1, dp(56)));
        panel.addView(model, new LinearLayout.LayoutParams(-1, dp(56)));
        panel.addView(ollama, new LinearLayout.LayoutParams(-1, dp(56)));
        panel.addView(ollamaModel, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout quick = row();
        Button heuristic = smallButton("Heuristic");
        heuristic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { provider.setText("Heuristic"); }
        });
        Button gemini = smallButton("Gemini");
        gemini.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { provider.setText("Gemini"); }
        });
        Button local = smallButton("Ollama");
        local.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { provider.setText("Ollama"); }
        });
        quick.addView(heuristic, new LinearLayout.LayoutParams(0, dp(40), 1));
        quick.addView(gemini, new LinearLayout.LayoutParams(0, dp(40), 1));
        quick.addView(local, new LinearLayout.LayoutParams(0, dp(40), 1));
        panel.addView(quick);

        panel.addView(text("Connection health", 13, Colors.MUTED, true));
        Button test = smallButton("Test selected provider");
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                saveProviderFields(provider, backupProvider, apiKey, model, ollama, ollamaModel);
                testSelectedProvider(provider.getText().toString(), apiKey.getText().toString(), model.getText().toString(), ollama.getText().toString(), ollamaModel.getText().toString());
            }
        });
        panel.addView(test, new LinearLayout.LayoutParams(-1, dp(42)));

        Button save = pillButton("Save provider settings");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                saveProviderFields(provider, backupProvider, apiKey, model, ollama, ollamaModel);
                setStatus("Provider settings saved.");
                refresh();
            }
        });
        panel.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void saveProviderFields(EditText provider, EditText backupProvider, EditText apiKey, EditText model, EditText ollama, EditText ollamaModel) {
        prefs.setProvider(provider.getText().toString());
        prefs.setBackupProvider(backupProvider.getText().toString());
        prefs.setGeminiApiKey(apiKey.getText().toString());
        prefs.setGeminiModel(model.getText().toString());
        prefs.setOllamaEndpoint(ollama.getText().toString());
        prefs.setOllamaModel(ollamaModel.getText().toString());
    }

    private void testSelectedProvider(final String provider, final String apiKey, final String model, final String ollamaEndpoint, final String ollamaModel) {
        final String normalized = provider == null ? "" : provider.toLowerCase(Locale.US);
        if (normalized.contains("heuristic") || normalized.trim().isEmpty()) {
            setStatus("Heuristic provider is local and ready.");
            return;
        }
        setStatus("Testing provider connection...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String result;
                    if (normalized.contains("ollama")) {
                        result = ProviderConnectionTester.testOllama(ollamaEndpoint, ollamaModel);
                    } else if (normalized.contains("gemini")) {
                        result = ProviderConnectionTester.testGemini(apiKey, model);
                    } else {
                        throw new IllegalStateException("Unsupported provider: " + provider);
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() { setStatus(result); }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setStatus("Provider test failed: " + shortText(error.getMessage(), 90));
                        }
                    });
                }
            }
        }, "dayflow-provider-test").start();
    }

    private void renderSettingsData(LinearLayout panel) {
        addDataExportSettings(panel);
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
    }

    private void renderSettingsOther(LinearLayout panel) {
        panel.addView(text("App preferences", 13, Colors.MUTED, true));
        final Switch analytics = new Switch(this);
        analytics.setText("Share crash reports and anonymous usage data");
        analytics.setTextColor(Colors.TEXT);
        analytics.setTypeface(DayflowType.sans(this));
        analytics.setChecked(prefs.analyticsEnabled());
        panel.addView(analytics);
        final Switch icons = new Switch(this);
        icons.setText("Show app/website icons in timeline");
        icons.setTextColor(Colors.TEXT);
        icons.setTypeface(DayflowType.sans(this));
        icons.setChecked(prefs.showTimelineAppIcons());
        panel.addView(icons);
        final Switch dailyPopups = new Switch(this);
        dailyPopups.setText("Show daily goal popups");
        dailyPopups.setTextColor(Colors.TEXT);
        dailyPopups.setTypeface(DayflowType.sans(this));
        dailyPopups.setChecked(prefs.showDailyGoalPopups());
        panel.addView(dailyPopups);

        final EditText language = field("Output language override", prefs.outputLanguageOverride(), true);
        panel.addView(language, new LinearLayout.LayoutParams(-1, dp(54)));

        Button save = pillButton("Save app preferences");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setAnalyticsEnabled(analytics.isChecked());
                prefs.setShowTimelineAppIcons(icons.isChecked());
                prefs.setShowDailyGoalPopups(dailyPopups.isChecked());
                prefs.setOutputLanguageOverride(language.getText().toString());
                setStatus("App preferences saved.");
                refresh();
            }
        });
        panel.addView(save, new LinearLayout.LayoutParams(-1, dp(44)));

        addJournalReminderSettings(panel);
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
        Button resume = pillButton("Resume recording");
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.resumeRecording();
                setStatus("Recording resumed.");
                refresh();
            }
        });
        panel.addView(resume, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void addDataExportSettings(LinearLayout panel) {
        panel.addView(text("Export your data", 13, Colors.MUTED, true));
        panel.addView(text("Use Markdown exports to archive in Notion, share with teammates, or paste into ChatGPT, Claude, or Gemini for deeper analysis.", 14, Colors.TEXT, false));

        final EditText exportFrom = field("From yyyy-MM-dd", selectedDay, true);
        final EditText exportTo = field("To yyyy-MM-dd", selectedDay, true);
        LinearLayout exportDates = row();
        exportDates.addView(exportFrom, new LinearLayout.LayoutParams(0, dp(54), 1));
        exportDates.addView(exportTo, new LinearLayout.LayoutParams(0, dp(54), 1));
        panel.addView(exportDates);

        LinearLayout exportActions = row();
        Button copy = smallButton("Copy");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String from = normalizedDay(exportFrom.getText().toString(), selectedDay);
                String to = normalizedDay(exportTo.getText().toString(), selectedDay);
                if (from.compareTo(to) > 0) {
                    setStatus("Export start must be on or before end.");
                    return;
                }
                copyText("Dayflow export", db.exportMarkdownRange(from, to));
            }
        });
        Button save = pillButton("Export as Markdown");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String from = normalizedDay(exportFrom.getText().toString(), selectedDay);
                String to = normalizedDay(exportTo.getText().toString(), selectedDay);
                if (from.compareTo(to) > 0) {
                    setStatus("Export start must be on or before end.");
                    return;
                }
                startMarkdownExport(from, to);
            }
        });
        exportActions.addView(copy, new LinearLayout.LayoutParams(dp(96), dp(44)));
        exportActions.addView(save, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(exportActions);

        panel.addView(text("Reprocess day", 13, Colors.MUTED, true));
        panel.addView(text("Clears existing cards and observations for one timeline day, then reruns analysis from the saved screenshots. This can consume API calls when Gemini or Ollama is selected.", 14, Colors.TEXT, false));
        final EditText reprocessDay = field("Day yyyy-MM-dd", selectedDay, true);
        panel.addView(reprocessDay, new LinearLayout.LayoutParams(-1, dp(54)));
        Button reprocessExact = pillButton("Reprocess day");
        reprocessExact.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                confirmReprocessDay(normalizedDay(reprocessDay.getText().toString(), selectedDay));
            }
        });
        panel.addView(reprocessExact, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void startMarkdownExport(String fromDay, String toDay) {
        pendingExportMarkdown = db.exportMarkdownRange(fromDay, toDay);
        pendingExportLabel = "Dayflow timeline " + fromDay + " to " + toDay + ".md";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/markdown")
                .putExtra(Intent.EXTRA_TITLE, pendingExportLabel);
        try {
            startActivityForResult(intent, REQ_EXPORT_MARKDOWN);
            setStatus("Choose where to save the Markdown export.");
        } catch (Exception ignored) {
            copyText("Dayflow export", pendingExportMarkdown);
            pendingExportMarkdown = null;
            pendingExportLabel = null;
            setStatus("No file picker available, copied export instead.");
        }
    }

    private void writePendingExport(Uri uri) {
        try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
            if (stream == null) throw new IOException("No output stream");
            stream.write(pendingExportMarkdown.getBytes(StandardCharsets.UTF_8));
            setStatus("Exported " + pendingExportLabel + ".");
        } catch (Exception error) {
            setStatus("Export failed: " + shortText(error.getMessage(), 80));
        } finally {
            pendingExportMarkdown = null;
            pendingExportLabel = null;
        }
    }

    private void confirmReprocessDay(final String day) {
        new AlertDialog.Builder(this)
                .setTitle("Reprocess " + day + "?")
                .setMessage("Existing cards and observations for this timeline day will be rebuilt from saved screenshots. AI providers may make new API calls.")
                .setPositiveButton("Reprocess", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        setStatus("Reprocessing " + day + "...");
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final int count = new AnalysisEngine(MainActivity.this).reprocessDay(day);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        selectedDay = day;
                                        setStatus(count > 0 ? "Reprocessed " + count + " batches." : "No existing batches found; scanned for new screenshots.");
                                        refresh();
                                    }
                                });
                            }
                        }, "dayflow-reprocess").start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addJournalReminderSettings(LinearLayout panel) {
        panel.addView(text("Journal reminders", 13, Colors.MUTED, true));
        panel.addView(text(JournalReminderScheduler.reminderSummary(this), 14, Colors.TEXT, false));

        final Switch enabled = new Switch(this);
        enabled.setText("Recurring intention and reflection notifications");
        enabled.setTextColor(Colors.TEXT);
        enabled.setTextSize(13);
        enabled.setTypeface(DayflowType.sans(this));
        enabled.setChecked(prefs.journalRemindersEnabled());
        panel.addView(enabled);

        final EditText intentionHour = field("Intention hour", String.valueOf(prefs.journalIntentionHour()), true);
        final EditText intentionMinute = field("Intention minute", String.valueOf(prefs.journalIntentionMinute()), true);
        final EditText reflectionHour = field("Reflection hour", String.valueOf(prefs.journalReflectionHour()), true);
        final EditText reflectionMinute = field("Reflection minute", String.valueOf(prefs.journalReflectionMinute()), true);
        intentionHour.setInputType(InputType.TYPE_CLASS_NUMBER);
        intentionMinute.setInputType(InputType.TYPE_CLASS_NUMBER);
        reflectionHour.setInputType(InputType.TYPE_CLASS_NUMBER);
        reflectionMinute.setInputType(InputType.TYPE_CLASS_NUMBER);

        LinearLayout firstRow = row();
        firstRow.addView(intentionHour, new LinearLayout.LayoutParams(0, dp(54), 1));
        firstRow.addView(intentionMinute, new LinearLayout.LayoutParams(0, dp(54), 1));
        panel.addView(firstRow);

        LinearLayout secondRow = row();
        secondRow.addView(reflectionHour, new LinearLayout.LayoutParams(0, dp(54), 1));
        secondRow.addView(reflectionMinute, new LinearLayout.LayoutParams(0, dp(54), 1));
        panel.addView(secondRow);

        final Switch[] weekdays = new Switch[7];
        int[] calendarDays = new int[]{
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY,
                Calendar.SATURDAY,
                Calendar.SUNDAY};
        String[] labels = new String[]{"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int i = 0; i < weekdays.length; i++) {
            weekdays[i] = reminderDaySwitch(labels[i], calendarDays[i]);
            panel.addView(weekdays[i]);
        }

        LinearLayout actions = row();
        Button save = pillButton("Save reminders");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setJournalRemindersEnabled(enabled.isChecked());
                prefs.setJournalReminderTimes(
                        parseInt(intentionHour.getText().toString(), prefs.journalIntentionHour()),
                        parseInt(intentionMinute.getText().toString(), prefs.journalIntentionMinute()),
                        parseInt(reflectionHour.getText().toString(), prefs.journalReflectionHour()),
                        parseInt(reflectionMinute.getText().toString(), prefs.journalReflectionMinute()));
                for (Switch weekday : weekdays) {
                    Integer day = (Integer) weekday.getTag();
                    prefs.setJournalWeekdayEnabled(day, weekday.isChecked());
                }
                if (enabled.isChecked()) {
                    maybeRequestNotifications();
                    JournalReminderScheduler.reschedule(MainActivity.this);
                    setStatus("Journal reminders scheduled.");
                } else {
                    JournalReminderScheduler.cancel(MainActivity.this);
                    setStatus("Journal reminders disabled.");
                }
                refresh();
            }
        });
        Button test = smallButton("Test");
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                sendBroadcast(new Intent(MainActivity.this, JournalReminderReceiver.class)
                        .setAction(JournalReminderScheduler.ACTION_REMINDER)
                        .putExtra(JournalReminderScheduler.EXTRA_KIND, JournalReminderScheduler.KIND_REFLECTIONS));
                setStatus("Test reminder sent.");
            }
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(test, new LinearLayout.LayoutParams(dp(90), dp(44)));
        panel.addView(actions);
    }

    private Switch reminderDaySwitch(String label, int calendarDay) {
        Switch day = new Switch(this);
        day.setText(label);
        day.setTextColor(Colors.TEXT);
        day.setTextSize(13);
        day.setTypeface(DayflowType.sans(this));
        day.setTag(calendarDay);
        day.setChecked(prefs.journalReminderIncludesWeekday(calendarDay));
        return day;
    }

    private Button providerChoiceButton(String label, final String providerName, final int nextStep) {
        Button button = providerName.equalsIgnoreCase(prefs.provider()) ? pillButton(label) : smallButton(label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setProvider(providerName);
                if ("Heuristic".equalsIgnoreCase(providerName)) prefs.setBackupProvider("Heuristic");
                goOnboarding(nextStep);
            }
        });
        return button;
    }

    private void addOnboardingBack(LinearLayout panel, final int step) {
        Button back = smallButton("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { goOnboarding(step); }
        });
        panel.addView(back, new LinearLayout.LayoutParams(-1, dp(42)));
    }

    private void goOnboarding(int step) {
        prefs.setOnboardingStep(step);
        selectedTab = "Onboarding";
        refresh();
    }

    private void completeOnboarding() {
        db.createOnboardingCard(prefs.provider());
        prefs.setDidOnboard(true);
        prefs.setOnboardingStep(0);
        selectedDay = TimeUtil.dayKey(System.currentTimeMillis());
        selectedTab = "Timeline";
        buildUi();
        setStatus("Setup complete. Start recording when you are ready.");
        refresh();
    }

    private String onboardingTitle(int step) {
        switch (step) {
            case 0: return "Welcome";
            case 1: return "Role";
            case 2: return "Preferences";
            case 3: return "AI Provider";
            case 4: return "Provider Setup";
            case 5: return "Categories";
            case 6: return "Permissions";
            case 7: return "Completion";
            default: return "Welcome";
        }
    }

    private String onboardingSubtitle(int step) {
        switch (step) {
            case 0: return "See the product shape before giving it access.";
            case 1: return "Pick the lens Dayflow should use for your work.";
            case 2: return "Save a tiny bit of context for provider recommendations.";
            case 3: return "Choose local-only, cloud vision, or local vision.";
            case 4: return "Add the exact model details and backup route.";
            case 5: return "Review labels and colors before cards are created.";
            case 6: return "Grant Usage Access and start Android screen capture.";
            case 7: return "Finish setup and enter the Dayflow timeline.";
            default: return "";
        }
    }

    private void addAssetImage(LinearLayout panel, String assetPath, int heightDp) {
        try (InputStream stream = getAssets().open(assetPath)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) return;
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            image.setBackgroundColor(Colors.CARD_ALT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(heightDp));
            lp.setMargins(0, dp(12), 0, dp(12));
            panel.addView(image, lp);
        } catch (IOException ignored) {
        }
    }

    private void renderCardList(List<TimelineCard> cards) {
        for (final TimelineCard card : cards) {
            LinearLayout p = panel();
            p.addView(text(TimeUtil.timeLabel(card.startMs) + " - " + TimeUtil.timeLabel(card.endMs) + " · " + card.category, 12, Colors.MUTED, true));
            p.addView(serif(card.title, 24, Colors.TEXT));
            p.addView(text(card.summary == null ? "" : card.summary, 14, Colors.TEXT, false));
            p.addView(cardActions(card));
            content.addView(p);
            addGap(10);
        }
    }

    private LinearLayout cardActions(final TimelineCard card) {
        LinearLayout actions = row();
        Button category = smallButton("Category");
        category.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showCategoryPicker(card); }
        });
        Button video = smallButton("Video");
        video.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openOrGenerateTimelapse(card, true); }
        });
        Button delete = smallButton("Delete");
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { confirmDeleteCard(card); }
        });
        actions.addView(video, new LinearLayout.LayoutParams(0, dp(40), 1));
        actions.addView(category, new LinearLayout.LayoutParams(0, dp(40), 1));
        actions.addView(delete, new LinearLayout.LayoutParams(dp(100), dp(40)));
        return actions;
    }

    private void showCategoryPicker(final TimelineCard card) {
        final List<Category> categories = db.fetchCategories();
        if (categories.isEmpty()) {
            setStatus("No categories available.");
            return;
        }
        CharSequence[] labels = new CharSequence[categories.size()];
        for (int i = 0; i < categories.size(); i++) {
            labels[i] = categories.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("Move card to category")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Category category = categories.get(which);
                        db.updateTimelineCardCategory(card.id, category.name);
                        setStatus("Card moved to " + category.name + ".");
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteCard(final TimelineCard card) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this card?")
                .setMessage(TimeUtil.timeLabel(card.startMs) + " - " + TimeUtil.timeLabel(card.endMs) + "\n" + card.title)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        db.deleteTimelineCard(card.id);
                        setStatus("Card deleted.");
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    private void addReviewScrubber(LinearLayout panel, TimelineCard card) {
        List<ScreenshotRecord> frames = db.screenshotsInRange(
                card.startMs - TimeUtil.MINUTE,
                card.endMs + TimeUtil.MINUTE,
                90);
        if (frames.isEmpty()) return;
        TimelineReviewScrubberView scrubber = new TimelineReviewScrubberView(this);
        scrubber.setData(card, frames);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(220));
        lp.setMargins(0, dp(10), 0, dp(12));
        panel.addView(scrubber, lp);
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

    private void addTimelapseControls(LinearLayout panel, final TimelineCard card) {
        File existing = existingTimelapse(card);
        LinearLayout actions = row();
        Button play = existing == null ? pillButton("Generate timelapse") : pillButton("Play timelapse");
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openOrGenerateTimelapse(card, true); }
        });
        actions.addView(play, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button regenerate = smallButton("Regenerate");
        regenerate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { generateTimelapse(card, true); }
        });
        actions.addView(regenerate, new LinearLayout.LayoutParams(dp(120), dp(42)));
        panel.addView(actions);
        if (existing != null) {
            panel.addView(text("Saved video summary · " + bytes(existing.length()), 12, Colors.MUTED, false));
        }
    }

    private File existingTimelapse(TimelineCard card) {
        if (card.videoSummaryPath == null || card.videoSummaryPath.trim().isEmpty()) return null;
        File file = new File(card.videoSummaryPath);
        return file.isFile() && file.length() > 0 ? file : null;
    }

    private void openOrGenerateTimelapse(TimelineCard card, boolean playWhenDone) {
        File existing = existingTimelapse(card);
        if (existing != null) {
            playTimelapse(existing, card);
            return;
        }
        generateTimelapse(card, playWhenDone);
    }

    private void generateTimelapse(final TimelineCard card, final boolean playWhenDone) {
        setStatus("Generating timelapse...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final File video = new TimelapseGenerator(MainActivity.this).generateForCard(db, card);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            card.videoSummaryPath = video.getAbsolutePath();
                            setStatus("Timelapse ready.");
                            refresh();
                            if (playWhenDone) playTimelapse(video, card);
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setStatus("Timelapse failed: " + shortText(error.getMessage(), 80));
                        }
                    });
                }
            }
        }, "dayflow-timelapse").start();
    }

    private void playTimelapse(File file, TimelineCard card) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), 0);
        final VideoView video = new VideoView(this);
        video.setBackgroundColor(Color.WHITE);
        MediaController controls = new MediaController(this);
        controls.setAnchorView(video);
        video.setMediaController(controls);
        video.setVideoURI(Uri.fromFile(file));
        panel.addView(video, new LinearLayout.LayoutParams(-1, dp(320)));
        panel.addView(text(TimeUtil.timeLabel(card.startMs) + " - " + TimeUtil.timeLabel(card.endMs) + " · " + card.category, 12, Colors.MUTED, false));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(card.title == null ? "Timelapse" : card.title)
                .setView(panel)
                .setNegativeButton("Close", null)
                .create();
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                mp.setLooping(true);
                video.start();
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialogInterface) {
                video.stopPlayback();
            }
        });
        dialog.show();
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

    private LinearLayout goalLine(String label, long actualMs, long targetMs, int color, boolean higherIsBetter) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, dp(8), 0, dp(8));
        float ratio = targetMs <= 0 ? 0f : actualMs / (float) targetMs;
        int percent = Math.round(Math.min(1f, ratio) * 100f);
        String targetLabel = higherIsBetter ? "target" : "limit";
        layout.addView(text(label + ": " + TimeUtil.shortDuration(actualMs) + " / " + TimeUtil.shortDuration(targetMs) + " " + targetLabel, 14, Colors.TEXT, false));
        layout.addView(progressBar(color, ratio), new LinearLayout.LayoutParams(-1, dp(12)));
        layout.addView(text(percent + "%", 12, Colors.MUTED, false));
        return layout;
    }

    private String goalStatus(DashboardMetrics metrics, DayGoal goal) {
        long focusTargetMs = goal.focusTargetMinutes * TimeUtil.MINUTE;
        long distractionLimitMs = goal.distractionLimitMinutes * TimeUtil.MINUTE;
        boolean focusDone = focusTargetMs > 0 && metrics.productiveMs >= focusTargetMs;
        boolean distractionOver = distractionLimitMs > 0 && metrics.distractionMs > distractionLimitMs;
        if (focusDone && !distractionOver) return "Focus target reached while staying inside the distraction limit.";
        if (focusDone) return "Focus target reached, but distraction time is over the limit.";
        if (distractionOver) return "Distraction limit is already exceeded. Protect the next block.";
        return "Goals are in progress. Keep building focused blocks.";
    }

    private LinearLayout progressBar(int color, float fraction) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setPadding(0, dp(3), 0, dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Colors.CARD_ALT);
        bg.setStroke(1, Colors.STROKE);
        bg.setCornerRadius(dp(8));
        track.setBackground(bg);

        View fill = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(color);
        fillBg.setCornerRadius(dp(6));
        fill.setBackground(fillBg);
        int filled = Math.max(1, Math.round(Math.min(1f, Math.max(0f, fraction)) * 100f));
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, filled));
        Space rest = new Space(this);
        track.addView(rest, new LinearLayout.LayoutParams(0, -1, Math.max(1, 100 - filled)));
        return track;
    }

    private DashboardMetrics metricsFor(List<TimelineCard> cards) {
        DashboardMetrics metrics = new DashboardMetrics();
        metrics.cardCount = cards == null ? 0 : cards.size();
        if (cards == null) return metrics;
        for (TimelineCard card : cards) {
            long duration = card.durationMs();
            metrics.trackedMs += duration;
            String category = card.category == null ? "Work" : card.category;
            addDuration(metrics.categoryMs, category, duration);
            if (isUseful(card)) metrics.productiveMs += duration;
            if (isDistraction(card)) metrics.distractionMs += duration;
            String app = appFromMetadata(card.metadata);
            if (app != null) addDuration(metrics.appMs, app, duration);
        }
        return metrics;
    }

    private TimelineCard longestUsefulCard(List<TimelineCard> cards) {
        TimelineCard best = null;
        for (TimelineCard card : cards) {
            if (!isUseful(card)) continue;
            if (best == null || card.durationMs() > best.durationMs()) best = card;
        }
        return best;
    }

    private TimelineCard longestDistractionCard(List<TimelineCard> cards) {
        TimelineCard best = null;
        for (TimelineCard card : cards) {
            if (!isDistraction(card)) continue;
            if (best == null || card.durationMs() > best.durationMs()) best = card;
        }
        return best;
    }

    private List<TimelineCard> usefulCardsByDuration(List<TimelineCard> cards) {
        List<TimelineCard> copy = new ArrayList<>();
        for (TimelineCard card : cards) {
            if (isUseful(card)) copy.add(card);
        }
        java.util.Collections.sort(copy, new java.util.Comparator<TimelineCard>() {
            @Override public int compare(TimelineCard a, TimelineCard b) {
                return Long.compare(b.durationMs(), a.durationMs());
            }
        });
        return copy;
    }

    private String busiestDayLabel(long weekStart, List<TimelineCard> cards) {
        long[] totals = new long[7];
        for (TimelineCard card : cards) {
            int day = (int) ((card.startMs - weekStart) / TimeUtil.DAY);
            if (day >= 0 && day < totals.length) totals[day] += card.durationMs();
        }
        int best = 0;
        for (int i = 1; i < totals.length; i++) {
            if (totals[i] > totals[best]) best = i;
        }
        String[] labels = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        return labels[best] + " · " + TimeUtil.shortDuration(totals[best]);
    }

    private int countCardsInCategory(List<TimelineCard> cards, String category) {
        int count = 0;
        for (TimelineCard card : cards) {
            if (category != null && category.equals(card.category)) count++;
        }
        return count;
    }

    private Map<String, Long> appTransitions(List<TimelineCard> cards) {
        List<TimelineCard> copy = new ArrayList<>(cards);
        java.util.Collections.sort(copy, new java.util.Comparator<TimelineCard>() {
            @Override public int compare(TimelineCard a, TimelineCard b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        Map<String, Long> transitions = new LinkedHashMap<>();
        String previous = null;
        for (TimelineCard card : copy) {
            String app = appFromMetadata(card.metadata);
            if (app == null || app.trim().isEmpty()) continue;
            if (previous != null && !previous.equals(app)) {
                addDuration(transitions, previous + " → " + app, 1);
            }
            previous = app;
        }
        return transitions;
    }

    private <K> List<Map.Entry<K, Long>> topEntries(Map<K, Long> map, int limit) {
        List<Map.Entry<K, Long>> entries = DayflowDatabase.sortedByDuration(map);
        if (entries.size() <= limit) return entries;
        return entries.subList(0, limit);
    }

    private static void addDuration(Map<String, Long> map, String key, long value) {
        Long current = map.get(key);
        map.put(key, current == null ? value : current + value);
    }

    private static boolean isUseful(TimelineCard card) {
        return !isDistraction(card) && !isIdle(card);
    }

    private static boolean isDistraction(TimelineCard card) {
        return normalizedCategory(card).contains("distraction");
    }

    private static boolean isIdle(TimelineCard card) {
        return normalizedCategory(card).contains("idle");
    }

    private static String normalizedCategory(TimelineCard card) {
        return (card.category == null ? "" : card.category).toLowerCase(Locale.US);
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

    private static String shortText(String value, int max) {
        String text = value == null || value.trim().isEmpty() ? "Continue the most recent useful thread." : value.trim();
        if (text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String normalizedDay(String value, String fallback) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        try {
            Date date = format.parse(value == null ? "" : value.trim());
            return format.format(date);
        } catch (Exception ignored) {
            try {
                Date date = format.parse(fallback == null ? "" : fallback.trim());
                return format.format(date);
            } catch (Exception ignoredAgain) {
                return TimeUtil.dayKey(System.currentTimeMillis());
            }
        }
    }

    private static String bytes(long value) {
        if (value >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", value / (1024f * 1024f));
        if (value >= 1024L) return String.format(Locale.US, "%.1f KB", value / 1024f);
        return value + " B";
    }

    private String appVersionLabel() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "0.1.0";
        }
    }

    private static int parseColor(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
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
