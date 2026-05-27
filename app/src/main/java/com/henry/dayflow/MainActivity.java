package com.henry.dayflow;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.media.MediaPlayer;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
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
    private static final int DAILY_REQUIRED_BATCHES = 20;
    private static final String[] CATEGORY_COLOR_PRESETS = {
            "#B984FF", "#6AADFF", "#FFAE8C", "#FF5950", "#42D0BB", "#F96E00", "#90DDF0", "#A0AEC0"
    };

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
        addOnboardingPreview(panel, "images/onboarding/timeline.png", "Timeline builds itself", "15-minute batches become readable cards with summaries, categories, and screenshots.");
        addHowItWorksCards(panel);
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
        panel.addView(text("Pick the lens Dayflow should use when it names work, highlights focus, and writes your daily summaries.", 14, Colors.MUTED, false));
        panel.addView(onboardingInfoCard("images/onboarding/understanding.png", "Understand your day", "The same screen history can mean deep work, class, design review, or research depending on what you care about."));
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
        panel.addView(text("Dayflow asks how you found it and how you want analysis to run before recommending a provider.", 14, Colors.MUTED, false));
        panel.addView(onboardingInfoCard("images/onboarding/security.png", "Local-first setup", "You can keep analysis offline with the built-in heuristic or Ollama, then add Gemini later when you want richer visual reading."));

        final EditText referral = field("How did you hear about Dayflow?", prefs.onboardingReferral(), false);
        panel.addView(referral, new LinearLayout.LayoutParams(-1, dp(92)));

        final Switch localFirst = new Switch(this);
        localFirst.setText("Prefer local/offline analysis first");
        localFirst.setTextColor(Colors.TEXT);
        localFirst.setTextSize(13);
        localFirst.setTypeface(DayflowType.sans(this));
        localFirst.setChecked(prefs.onboardingPreferLocalFirst());
        panel.addView(localFirst);

        LinearLayout actions = row();
        Button back = smallButton("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { goOnboarding(1); }
        });
        Button next = pillButton("Continue");
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setOnboardingReferral(referral.getText().toString());
                prefs.setOnboardingPreferLocalFirst(localFirst.isChecked());
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
        panel.addView(onboardingInfoCard("images/onboarding/how.png", "Install and forget", "Once permissions are enabled, Dayflow keeps gathering private local context in the background while you use Android normally."));
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
        List<Category> categories = db.fetchCategories();
        panel.addView(categoryPillStrip(categories));
        for (Category category : categories) {
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
        panel.addView(onboardingInfoCard("images/onboarding/security.png", "Privacy by default", "Screenshots stay on this Android device, retention controls can purge old files, and blocked apps are redacted before storage."));
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
        addOnboardingPreview(panel, "images/onboarding/journal.png", "Journal from the same day", "Intentions, notes, reflections, and generated summaries stay tied to the timeline.");
        addOnboardingPreview(panel, "images/onboarding/weekly_calendar.jpg", "Weekly patterns", "Heatmaps, context shifts, app flows, and review data roll up after enough history is captured.");
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

        ReviewSnapshot reviewSnapshot = db.reviewSnapshot(selectedDay, cards);
        if (reviewSnapshot.unreviewedCards > 0) {
            content.addView(cardsToReviewButton(reviewSnapshot.unreviewedCards), new LinearLayout.LayoutParams(-1, dp(48)));
            addGap(10);
        }

        TimelineCanvasView timeline = new TimelineCanvasView(this);
        timeline.setCards(selectedDay, cards);
        content.addView(timeline, new LinearLayout.LayoutParams(-1, dp(24 * 92)));
        addGap(14);
        renderCardList(cards);
    }

    private void renderDaily(List<TimelineCard> cards) {
        if (!prefs.dailyUnlocked()) {
            renderDailyAccess();
            return;
        }
        DashboardMetrics metrics = metricsFor(cards);
        renderDailyGoals(metrics);
        renderDailyFocusSummary(cards, metrics);
        renderDailyDistractionSummary(cards, metrics);

        DailyWorkflowView workflow = new DailyWorkflowView(this);
        workflow.setCards(selectedDay, cards);
        content.addView(workflow, new LinearLayout.LayoutParams(-1, dp(520)));
        addGap(14);

        renderDailyStandup(cards);
        addGap(14);
        renderCardList(cards);
    }

    private void renderDailyAccess() {
        int analyzed = db.countAnalyzedBatches();
        int capped = Math.min(DAILY_REQUIRED_BATCHES, Math.max(0, analyzed));
        boolean ready = analyzed >= DAILY_REQUIRED_BATCHES;
        float progress = capped / (float) DAILY_REQUIRED_BATCHES;

        LinearLayout panel = panel();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        DayflowLogoView logo = new DayflowLogoView(this);
        panel.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        panel.addView(serif("Dayflow Daily", 34, Colors.TEXT));
        panel.addView(text("BETA", 12, Colors.ACCENT, true));
        panel.addView(text("Daily is a new way to visualize your day and turn it into a standup update fast.", 15, Colors.TEXT, false));
        panel.addView(text("Daily unlocks after 5 hours of analyzed timeline data. " + dailyProgressText(analyzed), 13, Colors.MUTED, false));
        panel.addView(progressBar(Colors.ACCENT, progress), new LinearLayout.LayoutParams(-1, dp(14)));

        addAssetImage(panel, "images/dayflow_content_area.png", 170);

        LinearLayout checks = new LinearLayout(this);
        checks.setOrientation(LinearLayout.VERTICAL);
        checks.setPadding(0, dp(8), 0, dp(8));
        checks.addView(text("READINESS", 12, Colors.MUTED, true));
        checks.addView(text("Usage Access: " + (appReader.hasUsageAccess() ? "enabled" : "needed")
                + "\nScreen capture: start when you are ready"
                + "\nNotifications: " + (hasNotificationPermission() ? "enabled" : "needed")
                + "\nDaily provider: " + prefs.provider(), 14, Colors.TEXT, false));
        panel.addView(checks);

        LinearLayout providerRow = row();
        Button heuristic = smallButton("Heuristic");
        heuristic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setProvider("Heuristic");
                setStatus("Daily provider set to local heuristic.");
                refresh();
            }
        });
        Button gemini = smallButton("Gemini");
        gemini.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setProvider("Gemini");
                setStatus("Daily provider set to Gemini.");
                refresh();
            }
        });
        Button ollama = smallButton("Ollama");
        ollama.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setProvider("Ollama");
                setStatus("Daily provider set to Ollama.");
                refresh();
            }
        });
        providerRow.addView(heuristic, new LinearLayout.LayoutParams(0, dp(40), 1));
        providerRow.addView(gemini, new LinearLayout.LayoutParams(0, dp(40), 1));
        providerRow.addView(ollama, new LinearLayout.LayoutParams(0, dp(40), 1));
        panel.addView(providerRow);

        LinearLayout actions = row();
        Button usage = smallButton(appReader.hasUsageAccess() ? "Usage ready" : "Usage Access");
        usage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startActivity(appReader.usageAccessIntent()); }
        });
        Button capture = smallButton("Start capture");
        capture.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { requestScreenCapture(); }
        });
        Button analyze = smallButton("Analyze now");
        analyze.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                setStatus("Analyzing recent batches...");
                new Thread(new Runnable() {
                    @Override public void run() {
                        new AnalysisEngine(MainActivity.this).processNow();
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                setStatus("Analysis complete.");
                                refresh();
                            }
                        });
                    }
                }, "dayflow-daily-access-analysis").start();
            }
        });
        actions.addView(usage, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(capture, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(analyze, new LinearLayout.LayoutParams(0, dp(42), 1));
        panel.addView(actions);

        Button unlock = ready ? pillButton("Continue to Daily") : smallButton("Need " + (DAILY_REQUIRED_BATCHES - capped) + " more batches");
        unlock.setEnabled(ready);
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setDailyUnlocked(true);
                setStatus("Daily unlocked.");
                refresh();
            }
        });
        panel.addView(unlock, new LinearLayout.LayoutParams(-1, dp(46)));

        content.addView(panel);
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
            panel.addView(text("Focus: " + goalCategorySummary(goal.focusCategories) + "\nLimit: " + goalCategorySummary(goal.distractionCategories), 13, Colors.MUTED, false));
            panel.addView(text(goalStatus(metrics, goal), 14, Colors.TEXT, false));
        }

        LinearLayout actions = row();
        Button edit = pillButton("Edit goals");
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                showDayGoalFlow(goal, metrics);
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

    private String dailyProgressText(int analyzedBatches) {
        int minutes = Math.min(DAILY_REQUIRED_BATCHES, Math.max(0, analyzedBatches)) * 15;
        int hours = minutes / 60;
        int remaining = minutes % 60;
        String done;
        if (minutes == 0) done = "0h";
        else if (remaining == 0) done = hours + "h";
        else if (hours == 0) done = remaining + "m";
        else done = hours + "h " + remaining + "m";
        return done + " / 5h";
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void showDayGoalFlow(final DayGoal current, DashboardMetrics todayMetrics) {
        final DayGoal draft = copyGoal(current);
        final Map<String, String> assignment = goalAssignmentMap(draft);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(4), dp(4), dp(4), 0);

        String previousDay = offsetDay(selectedDay, -1);
        DashboardMetrics yesterday = db.dashboardForDay(previousDay);
        DayGoal previousGoal = db.fetchDayGoal(previousDay);
        DashboardMetrics lastWeekAverage = averageMetrics(selectedDay, 7);

        body.addView(serif("Yesterday's review", 28, Colors.TEXT));
        body.addView(goalReviewRow("Focus target", previousGoal.focusTargetMinutes * TimeUtil.MINUTE, yesterday.productiveMs, Colors.WORK, true));
        body.addView(goalReviewRow("Distraction limit", previousGoal.distractionLimitMinutes * TimeUtil.MINUTE, yesterday.distractionMs, Colors.DISTRACTION, false));
        body.addView(text("Last 7-day average: " + TimeUtil.shortDuration(lastWeekAverage.productiveMs) + " focus · "
                + TimeUtil.shortDuration(lastWeekAverage.distractionMs) + " distraction", 13, Colors.MUTED, false));

        addGapTo(body, 10);
        body.addView(serif("Where do you want to spend your time today?", 24, Colors.ACCENT));
        final EditText focusTarget = field("Focus target minutes", String.valueOf(draft.focusTargetMinutes), true);
        final EditText distractionLimit = field("Distraction limit minutes", String.valueOf(draft.distractionLimitMinutes), true);
        focusTarget.setInputType(InputType.TYPE_CLASS_NUMBER);
        distractionLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        body.addView(focusTarget, new LinearLayout.LayoutParams(-1, dp(54)));
        body.addView(distractionLimit, new LinearLayout.LayoutParams(-1, dp(54)));

        body.addView(text("Categories", 13, Colors.MUTED, true));
        body.addView(text("Tap each category into Focus, Distraction, or Off. This mirrors Dayflow's goal setup while staying touch-native.", 13, Colors.MUTED, false));
        final LinearLayout categoryList = new LinearLayout(this);
        categoryList.setOrientation(LinearLayout.VERTICAL);
        body.addView(categoryList);
        renderGoalCategoryAssignments(categoryList, assignment);

        final Switch skipped = new Switch(this);
        skipped.setText("Skip goals for this day");
        skipped.setTextColor(Colors.TEXT);
        skipped.setTextSize(13);
        skipped.setTypeface(DayflowType.sans(this));
        skipped.setChecked(draft.skipped);
        body.addView(skipped);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle("Day goals")
                .setView(scroll)
                .setPositiveButton("Confirm", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        draft.focusTargetMinutes = parseInt(focusTarget.getText().toString(), draft.focusTargetMinutes);
                        draft.distractionLimitMinutes = parseInt(distractionLimit.getText().toString(), draft.distractionLimitMinutes);
                        draft.skipped = skipped.isChecked();
                        applyGoalAssignments(draft, assignment);
                        db.saveDayGoal(draft);
                        setStatus(draft.skipped ? "Goals skipped for today." : "Goals saved.");
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Skip today", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        draft.skipped = true;
                        applyGoalAssignments(draft, assignment);
                        db.saveDayGoal(draft);
                        setStatus("Goals skipped for today.");
                        refresh();
                    }
                })
                .show();
    }

    private void renderGoalCategoryAssignments(LinearLayout list, final Map<String, String> assignment) {
        list.removeAllViews();
        for (Category category : db.fetchCategories()) {
            if (category.system || category.idle) continue;
            final String name = category.name;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, dp(8), 0, dp(8));

            TextView label = text(name, 14, Colors.TEXT, false);
            GradientDrawable swatch = new GradientDrawable();
            swatch.setColor(parseColor(category.colorHex, Colors.colorForCategory(category.name)));
            swatch.setCornerRadius(dp(5));
            label.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            item.addView(label);

            final Button focus = smallButton("Focus");
            final Button distraction = smallButton("Distraction");
            final Button off = smallButton("Off");
            final Button[] buttons = new Button[]{focus, distraction, off};
            final int color = parseColor(category.colorHex, Colors.colorForCategory(category.name));
            updateGoalAssignmentButtons(buttons, assignment.get(name), color);
            focus.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    assignment.put(name, "focus");
                    updateGoalAssignmentButtons(buttons, "focus", color);
                }
            });
            distraction.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    assignment.put(name, "distraction");
                    updateGoalAssignmentButtons(buttons, "distraction", color);
                }
            });
            off.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    assignment.put(name, "off");
                    updateGoalAssignmentButtons(buttons, "off", color);
                }
            });
            LinearLayout buttonsRow = row();
            buttonsRow.addView(focus, new LinearLayout.LayoutParams(0, dp(38), 1));
            buttonsRow.addView(distraction, new LinearLayout.LayoutParams(0, dp(38), 1));
            buttonsRow.addView(off, new LinearLayout.LayoutParams(0, dp(38), 1));
            item.addView(buttonsRow);
            list.addView(item);
        }
    }

    private void updateGoalAssignmentButtons(Button[] buttons, String selected, int color) {
        String mode = selected == null ? "off" : selected;
        for (Button button : buttons) {
            String label = button.getText().toString().toLowerCase(Locale.US);
            boolean active = ("focus".equals(mode) && label.contains("focus"))
                    || ("distraction".equals(mode) && label.contains("distraction"))
                    || ("off".equals(mode) && label.contains("off"));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            if (active) {
                bg.setColor(ColorUtils.withAlpha(color, label.contains("off") ? 120 : 210));
                bg.setStroke(1, ColorUtils.withAlpha(color, 240));
                button.setTextColor(Colors.TEXT);
            } else {
                bg.setColor(Colors.CARD_ALT);
                bg.setStroke(1, Colors.STROKE);
                button.setTextColor(Colors.MUTED);
            }
            button.setBackground(bg);
        }
    }

    private LinearLayout goalReviewRow(String title, long targetMs, long actualMs, int color, boolean higherIsBetter) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(155, 255, 255, 255));
        bg.setStroke(1, Colors.STROKE);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(lp);

        float ratio = targetMs <= 0 ? 0 : Math.min(1f, actualMs / (float) targetMs);
        int percent = Math.round(ratio * 100f);
        String verdict = higherIsBetter
                ? (actualMs >= targetMs ? "Reached" : "In progress")
                : (actualMs <= targetMs ? "Inside limit" : "Over limit");
        card.addView(text(title + " · " + verdict, 13, Colors.MUTED, true));
        card.addView(serif(TimeUtil.shortDuration(actualMs) + " / " + TimeUtil.shortDuration(targetMs), 24, color));
        card.addView(progressBar(color, ratio), new LinearLayout.LayoutParams(-1, dp(12)));
        card.addView(text(percent + "% of target", 12, Colors.MUTED, false));
        return card;
    }

    private DayGoal copyGoal(DayGoal original) {
        DayGoal copy = new DayGoal();
        copy.day = original.day;
        copy.focusTargetMinutes = original.focusTargetMinutes;
        copy.distractionLimitMinutes = original.distractionLimitMinutes;
        copy.skipped = original.skipped;
        for (DayGoalCategory category : original.focusCategories) {
            copy.focusCategories.add(new DayGoalCategory(category.name, category.colorHex, category.order));
        }
        for (DayGoalCategory category : original.distractionCategories) {
            copy.distractionCategories.add(new DayGoalCategory(category.name, category.colorHex, category.order));
        }
        return copy;
    }

    private Map<String, String> goalAssignmentMap(DayGoal goal) {
        Map<String, String> assignment = new LinkedHashMap<>();
        for (Category category : db.fetchCategories()) {
            if (!category.system && !category.idle) assignment.put(category.name, "off");
        }
        for (DayGoalCategory category : goal.focusCategories) assignment.put(category.name, "focus");
        for (DayGoalCategory category : goal.distractionCategories) assignment.put(category.name, "distraction");
        return assignment;
    }

    private void applyGoalAssignments(DayGoal goal, Map<String, String> assignment) {
        goal.focusCategories.clear();
        goal.distractionCategories.clear();
        int focusOrder = 0;
        int distractionOrder = 0;
        for (Category category : db.fetchCategories()) {
            String mode = assignment.get(category.name);
            if ("focus".equals(mode)) {
                goal.focusCategories.add(new DayGoalCategory(category.name, category.colorHex, focusOrder++));
            } else if ("distraction".equals(mode)) {
                goal.distractionCategories.add(new DayGoalCategory(category.name, category.colorHex, distractionOrder++));
            }
        }
    }

    private String goalCategorySummary(List<DayGoalCategory> categories) {
        if (categories == null || categories.isEmpty()) return "None";
        StringBuilder out = new StringBuilder();
        for (DayGoalCategory category : categories) {
            if (out.length() > 0) out.append(", ");
            out.append(category.name);
            if (out.length() > 80) {
                out.append("...");
                break;
            }
        }
        return out.toString();
    }

    private DashboardMetrics averageMetrics(String anchorDay, int days) {
        int count = Math.max(1, days);
        DashboardMetrics total = metricsFor(db.fetchTimelineCardsRange(
                TimeUtil.dayStartMs(anchorDay) - count * TimeUtil.DAY,
                TimeUtil.dayStartMs(anchorDay)));
        total.trackedMs /= count;
        total.productiveMs /= count;
        total.distractionMs /= count;
        return total;
    }

    private String offsetDay(String day, int offset) {
        return TimeUtil.dayKey(TimeUtil.dayStartMs(day) + offset * TimeUtil.DAY + TimeUtil.HOUR);
    }

    private void addGapTo(LinearLayout parent, int gapDp) {
        Space space = new Space(this);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(gapDp)));
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
        final StandupDraft draft = parseStandupDraft(saved == null ? standupText(cards) : saved.content);

        LinearLayout standup = panel();
        standup.addView(text("DAILY STANDUP", 12, Colors.MUTED, true));
        standup.addView(serif("Standup for today", 26, Colors.ACCENT));
        standup.addView(text(saved == null ? "Draft from current timeline." : "Saved " + TimeUtil.timeLabel(saved.updatedAtMs), 13, Colors.MUTED, false));

        LinearLayout topActions = row();
        Button regenerate = smallButton("Regenerate");
        regenerate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { regenerateStandup(selectedDay); }
        });
        Button provider = smallButton("Provider: " + prefs.provider());
        provider.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setSettingsSection("Providers");
                selectedTab = "Settings";
                refresh();
            }
        });
        topActions.addView(regenerate, new LinearLayout.LayoutParams(0, dp(42), 1));
        topActions.addView(provider, new LinearLayout.LayoutParams(dp(148), dp(42)));
        standup.addView(topActions);

        final EditText highlights = standupEditor("Add highlight bullets", draft.highlights, 8);
        final EditText tasks = standupEditor("Add task bullets", draft.tasks, 6);
        final EditText blockers = standupEditor("Fill in any blockers", draft.blockers, 4);

        boolean wide = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density >= 640;
        if (wide) {
            LinearLayout cardRow = new LinearLayout(this);
            cardRow.setOrientation(LinearLayout.HORIZONTAL);
            cardRow.setGravity(Gravity.TOP);
            cardRow.setPadding(0, dp(8), 0, dp(12));

            LinearLayout highlightsCard = standupBulletCard("Yesterday's highlights", highlights, null);
            LinearLayout tasksCard = standupBulletCard("Today's tasks", tasks, blockers);
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1);
            leftLp.setMargins(0, 0, dp(8), 0);
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -2, 1);
            rightLp.setMargins(dp(8), 0, 0, 0);
            cardRow.addView(highlightsCard, leftLp);
            cardRow.addView(tasksCard, rightLp);
            standup.addView(cardRow);
        } else {
            LinearLayout highlightsCard = standupBulletCard("Yesterday's highlights", highlights, null);
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2);
            first.setMargins(0, dp(8), 0, dp(12));
            standup.addView(highlightsCard, first);
            standup.addView(standupBulletCard("Today's tasks", tasks, blockers), new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout actions = row();
        Button copy = pillButton("Copy");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                copyText("Dayflow standup", standupTextFromFields(highlights, tasks, blockers));
            }
        });
        Button save = smallButton("Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                db.saveDailyStandup(selectedDay, standupTextFromFields(highlights, tasks, blockers));
                setStatus("Standup saved.");
                refresh();
            }
        });
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(save, new LinearLayout.LayoutParams(dp(82), dp(44)));
        standup.addView(actions);
        content.addView(standup);
    }

    private LinearLayout standupBulletCard(String title, EditText primary, EditText blockers) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.argb(190, 255, 255, 255), Color.argb(235, 255, 253, 248), Color.argb(190, 255, 255, 255)});
        bg.setStroke(1, 0xffebe6e3);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);

        card.addView(serif(title, 24, Colors.ACCENT));
        card.addView(primary, new LinearLayout.LayoutParams(-1, -2));
        card.addView(text("Edit bullets directly, one per line.", 12, Colors.MUTED, false));

        if (blockers != null) {
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(dp(12), dp(12), dp(12), dp(12));
            GradientDrawable blockBg = new GradientDrawable();
            blockBg.setColor(0xfff7f6f5);
            blockBg.setStroke(1, 0xffebe6e3);
            blockBg.setCornerRadius(dp(10));
            block.setBackground(blockBg);
            block.addView(text("Blockers", 13, 0xffbd9479, true));
            block.addView(blockers, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(14), 0, 0);
            card.addView(block, lp);
        }
        return card;
    }

    private EditText standupEditor(String hint, String value, int minLines) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setText(value == null ? "" : value.trim());
        edit.setSingleLine(false);
        edit.setMinLines(minLines);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setTextColor(Colors.TEXT);
        edit.setHintTextColor(Colors.MUTED);
        edit.setTextSize(14);
        edit.setTypeface(DayflowType.sans(this));
        edit.setPadding(0, dp(10), 0, dp(10));
        edit.setBackgroundColor(Color.TRANSPARENT);
        return edit;
    }

    private String standupTextFromFields(EditText highlights, EditText tasks, EditText blockers) {
        return buildStandupText(
                highlights.getText().toString(),
                tasks.getText().toString(),
                blockers.getText().toString());
    }

    private String buildStandupText(String highlights, String tasks, String blockers) {
        return "Yesterday's highlights\n"
                + bulletsOrFallback(highlights, "No focused timeline blocks yet.")
                + "\nToday's tasks\n"
                + bulletsOrFallback(tasks, "Continue the highest-signal block from today")
                + "\nBlockers\n"
                + bulletsOrFallback(blockers, "No obvious blockers detected yet.");
    }

    private String bulletsOrFallback(String value, String fallback) {
        StringBuilder out = new StringBuilder();
        String source = value == null ? "" : value;
        for (String line : source.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (standupSectionFor(trimmed) != null) continue;
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
                out.append(trimmed).append("\n");
            } else {
                out.append("- ").append(trimmed).append("\n");
            }
        }
        if (out.length() == 0) out.append("- ").append(fallback).append("\n");
        return out.toString();
    }

    private StandupDraft parseStandupDraft(String raw) {
        StandupDraft draft = new StandupDraft();
        String section = "highlights";
        boolean foundSection = false;
        String source = raw == null ? "" : raw.replace('\r', '\n');
        for (String line : source.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String parsedSection = standupSectionFor(trimmed);
            if (parsedSection != null) {
                section = parsedSection;
                foundSection = true;
                continue;
            }
            appendStandupLine(draft, section, trimmed);
        }
        if (!foundSection && draft.isEmpty() && !source.trim().isEmpty()) {
            draft.highlights = source.trim();
        }
        if (draft.highlights.trim().isEmpty()) draft.highlights = "- No focused timeline blocks yet.";
        if (draft.tasks.trim().isEmpty()) draft.tasks = "- Continue the highest-signal block from today";
        if (draft.blockers.trim().isEmpty()) draft.blockers = "- No obvious blockers detected yet.";
        return draft;
    }

    private void appendStandupLine(StandupDraft draft, String section, String line) {
        if ("tasks".equals(section)) {
            draft.tasks = appendLine(draft.tasks, line);
        } else if ("blockers".equals(section)) {
            draft.blockers = appendLine(draft.blockers, line);
        } else {
            draft.highlights = appendLine(draft.highlights, line);
        }
    }

    private static String appendLine(String current, String line) {
        if (current == null || current.trim().isEmpty()) return line;
        return current + "\n" + line;
    }

    private String standupSectionFor(String line) {
        String normalized = line.toLowerCase(Locale.US)
                .replace('’', '\'')
                .replace("#", "")
                .replace("*", "")
                .replace(":", "")
                .trim();
        while (normalized.startsWith("-") || normalized.startsWith("•")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.contains("yesterday") && normalized.contains("highlight")) return "highlights";
        if ((normalized.contains("today") && (normalized.contains("task") || normalized.contains("priorit")))
                || normalized.equals("tasks")) return "tasks";
        if (normalized.contains("blocker")) return "blockers";
        return null;
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

        WeeklyDistributionView distribution = new WeeklyDistributionView(this);
        distribution.setCards(cards);
        content.addView(distribution, new LinearLayout.LayoutParams(-1, dp(300)));
        addGap(14);

        WeeklyCanvasView weekly = new WeeklyCanvasView(this);
        weekly.setCards(start, cards);
        content.addView(weekly, new LinearLayout.LayoutParams(-1, dp(520)));
        addGap(14);

        WeeklyOverviewFooterView overviewFooter = new WeeklyOverviewFooterView(this);
        overviewFooter.setCards(start, cards);
        content.addView(overviewFooter, new LinearLayout.LayoutParams(-1, dp(158)));
        addGap(14);

        WeeklyWorkflowView workflow = new WeeklyWorkflowView(this);
        workflow.setCards(start, cards);
        content.addView(workflow, new LinearLayout.LayoutParams(-1, dp(315)));
        addGap(14);

        WeeklyContextChartsView contextCharts = new WeeklyContextChartsView(this);
        contextCharts.setCards(start, cards);
        content.addView(contextCharts, new LinearLayout.LayoutParams(-1, dp(330)));
        addGap(14);

        WeeklyTreemapView treemap = new WeeklyTreemapView(this);
        treemap.setCards(cards, previousCards);
        content.addView(treemap, new LinearLayout.LayoutParams(-1, dp(430)));
        addGap(14);

        WeeklySankeyView sankey = new WeeklySankeyView(this);
        sankey.setCards(start, cards);
        content.addView(sankey, new LinearLayout.LayoutParams(-1, dp(520)));
        addGap(14);

        WeeklyInteractionGraphView graph = new WeeklyInteractionGraphView(this);
        graph.setCards(cards);
        content.addView(graph, new LinearLayout.LayoutParams(-1, dp(520)));
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

        WeeklyNarrativeView narrative = new WeeklyNarrativeView(this);
        narrative.setCards(cards);
        content.addView(narrative, new LinearLayout.LayoutParams(-1, dp(780)));

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

        LinearLayout panel = panel();
        panel.addView(serif(journalHeadline(), 34, Colors.TEXT));
        panel.addView(text("Dayflow helps you track your daily and longer term pursuits, gives you the space to reflect, and generates a summary of each day.", 14, Colors.MUTED, false));

        final EditText intentions = field("Intentions", entry.intentions, false);
        final EditText goals = field("Goals", entry.goals, false);
        final EditText notes = field("Notes", entry.notes, false);
        final EditText reflections = field("Reflections", entry.reflections, false);
        final EditText summary = field("Summary", entry.summary, false);

        LinearLayout board = new LinearLayout(this);
        board.setOrientation(isWideLayout() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        board.setGravity(Gravity.TOP);
        board.setPadding(0, dp(12), 0, dp(12));

        LinearLayout left = journalPaperCard();
        left.addView(serif("Today's intentions", 24, Colors.ACCENT));
        left.addView(text("What does a good day look like?", 13, Colors.MUTED, false));
        left.addView(intentions, new LinearLayout.LayoutParams(-1, dp(104)));
        left.addView(journalDivider());
        left.addView(serif("Notes for today", 22, Colors.ACCENT));
        left.addView(notes, new LinearLayout.LayoutParams(-1, dp(104)));
        left.addView(journalDivider());
        left.addView(serif("Long term goals", 22, Colors.ACCENT));
        left.addView(goals, new LinearLayout.LayoutParams(-1, dp(104)));

        LinearLayout right = journalPaperCard();
        right.addView(serif("Today's reflections", 24, Colors.ACCENT));
        right.addView(text("How was your day? What did you do? How do you feel?", 13, Colors.MUTED, false));
        right.addView(reflections, new LinearLayout.LayoutParams(-1, dp(132)));
        right.addView(journalDivider());
        right.addView(serif("Dayflow summary", 22, Colors.ACCENT));
        right.addView(text(metrics.trackedMs >= TimeUtil.HOUR
                ? "Summarize using your timeline, intentions, and reflections."
                : "Need at least 1 hour of timeline activity to summarize.", 13, Colors.MUTED, false));
        right.addView(summary, new LinearLayout.LayoutParams(-1, dp(132)));

        if (isWideLayout()) {
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1);
            leftLp.setMargins(0, 0, dp(6), 0);
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -2, 1);
            rightLp.setMargins(dp(6), 0, 0, 0);
            board.addView(left, leftLp);
            board.addView(right, rightLp);
        } else {
            LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(-1, -2);
            topLp.setMargins(0, 0, 0, dp(12));
            board.addView(left, topLp);
            board.addView(right, new LinearLayout.LayoutParams(-1, -2));
        }
        panel.addView(board);

        LinearLayout actions = row();
        Button save = pillButton("Save journal");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                saveJournalFields(intentions, goals, notes, reflections, summary, "saved");
                setStatus("Journal saved.");
            }
        });
        Button generate = smallButton(entry.summary == null || entry.summary.trim().isEmpty() ? "Summarize" : "Regenerate");
        generate.setEnabled(metrics.trackedMs >= TimeUtil.HOUR);
        generate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                saveJournalFields(intentions, goals, notes, reflections, summary, "reflection_saved");
                generateJournalSummary();
            }
        });
        Button reminders = smallButton("Reminders");
        reminders.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                prefs.setSettingsSection("Other");
                selectedTab = "Settings";
                refresh();
            }
        });
        Button copy = smallButton("Copy");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                copyText("Dayflow journal", journalText(cards, metrics, intentions.getText().toString(), goals.getText().toString(), notes.getText().toString(), reflections.getText().toString()));
            }
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(44), 1));
        actions.addView(generate, new LinearLayout.LayoutParams(dp(104), dp(44)));
        actions.addView(reminders, new LinearLayout.LayoutParams(dp(100), dp(44)));
        actions.addView(copy, new LinearLayout.LayoutParams(dp(72), dp(44)));
        panel.addView(actions);
        content.addView(panel);
    }

    private LinearLayout journalPaperCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.argb(120, 255, 255, 255), Color.argb(230, 255, 255, 255), Color.argb(120, 255, 255, 255)});
        bg.setStroke(1, Color.argb(210, 255, 255, 255));
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setElevation(dp(2));
        return card;
    }

    private View journalDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(150, 232, 225, 218));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.setMargins(0, dp(12), 0, dp(12));
        divider.setLayoutParams(lp);
        return divider;
    }

    private String journalHeadline() {
        return selectedDay.equals(TimeUtil.dayKey(System.currentTimeMillis()))
                ? "Today, " + new SimpleDateFormat("MMMM d", Locale.US).format(new Date())
                : "Journal · " + selectedDay;
    }

    private boolean isWideLayout() {
        return getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density >= 640;
    }

    private void saveJournalFields(EditText intentions, EditText goals, EditText notes, EditText reflections, EditText summary, String status) {
        JournalEntry saved = new JournalEntry();
        saved.day = selectedDay;
        saved.intentions = intentions.getText().toString();
        saved.goals = goals.getText().toString();
        saved.notes = notes.getText().toString();
        saved.reflections = reflections.getText().toString();
        saved.summary = summary.getText().toString();
        saved.status = status;
        db.saveJournal(saved);
    }

    private void generateJournalSummary() {
        final String day = selectedDay;
        setStatus("Generating journal summary...");
        new Thread(new Runnable() {
            @Override public void run() {
                final String generated = new ChatResponder(MainActivity.this).journalSummary(day);
                JournalEntry saved = db.fetchJournal(day);
                saved.day = day;
                saved.summary = generated;
                saved.status = "summary";
                db.saveJournal(saved);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setStatus("Journal summary generated.");
                        refresh();
                    }
                });
            }
        }, "dayflow-journal-summary").start();
    }

    private void renderReview(List<TimelineCard> cards) {
        final ReviewSnapshot snapshot = db.reviewSnapshot(selectedDay, cards);
        LinearLayout summary = panel();
        summary.addView(serif("Your review", 30, Colors.TEXT));
        summary.addView(text(reviewSubtitle(snapshot), 14, Colors.MUTED, false));
        ReviewSummaryView reviewBars = new ReviewSummaryView(this);
        reviewBars.setData(snapshot);
        LinearLayout.LayoutParams barsLp = new LinearLayout.LayoutParams(-1, dp(116));
        barsLp.setMargins(0, dp(6), 0, dp(4));
        summary.addView(reviewBars, barsLp);
        LinearLayout summaryActions = row();
        Button undo = smallButton("Undo last rating");
        undo.setEnabled(snapshot.hasData());
        undo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (db.undoLatestReviewRating(selectedDay)) {
                    setStatus("Latest review rating undone.");
                    refresh();
                } else {
                    setStatus("No review rating to undo.");
                }
            }
        });
        summaryActions.addView(undo, new LinearLayout.LayoutParams(0, dp(40), 1));
        summary.addView(summaryActions);
        content.addView(summary);

        if (cards.isEmpty()) {
            LinearLayout empty = panel();
            empty.addView(serif("No cards to review", 24, Colors.TEXT));
            empty.addView(text("Analyze a recording batch first, then come back here to rate the day.", 14, Colors.MUTED, false));
            content.addView(empty);
            return;
        }

        int index = 1;
        for (final TimelineCard card : cards) {
            LinearLayout p = panel();
            attachReviewSwipe(p, card);
            addReviewScrubber(p, card);
            LinearLayout meta = row();
            meta.addView(reviewChip(clean(card.category, "Uncategorized"), Colors.colorForCategory(card.category), ColorUtils.withAlpha(Colors.colorForCategory(card.category), 28)), new LinearLayout.LayoutParams(0, dp(30), 1));
            meta.addView(reviewChip(TimeUtil.timeLabel(card.startMs) + " - " + TimeUtil.timeLabel(card.endMs), Colors.STROKE, Colors.CARD_ALT), new LinearLayout.LayoutParams(0, dp(30), 1));
            p.addView(meta);
            TextView title = serif(card.title, 24, Colors.TEXT);
            attachReviewSwipe(title, card);
            p.addView(title);
            String currentRating = db.reviewRatingForCard(card);
            if (currentRating != null) {
                p.addView(reviewRatingChip("Rated " + currentRating, currentRating), new LinearLayout.LayoutParams(-1, dp(34)));
            }
            TextView summaryText = text(card.detailedSummary == null ? card.summary : card.detailedSummary, 14, Colors.TEXT, false);
            attachReviewSwipe(summaryText, card);
            p.addView(summaryText);
            addReviewFrames(p, card);
            addTimelapseControls(p, card);
            p.addView(text("Swipe left for Distracted, up for Neutral, right for Focused.", 12, Colors.MUTED, false));
            LinearLayout row = row();
            row.addView(ratingButton(card, "Distracted", "Distracted".equals(currentRating)), new LinearLayout.LayoutParams(0, dp(42), 1));
            row.addView(ratingButton(card, "Neutral", "Neutral".equals(currentRating)), new LinearLayout.LayoutParams(0, dp(42), 1));
            row.addView(ratingButton(card, "Focused", "Focused".equals(currentRating)), new LinearLayout.LayoutParams(0, dp(42), 1));
            p.addView(row);
            p.addView(text(index + " / " + cards.size(), 11, Colors.MUTED, true));
            p.addView(cardActions(card));
            content.addView(p);
            index++;
        }
    }

    private void renderCategories() {
        final List<Category> categories = db.fetchCategories();
        final List<TimelineCard> dayCards = db.fetchTimelineCards(selectedDay);
        LinearLayout intro = panel();
        intro.addView(serif("Categories", 30, Colors.TEXT));
        intro.addView(text("Tune the labels Dayflow uses when it builds timeline cards. Colors and descriptions also guide daily goals, review, charts, and chat context.", 14, Colors.MUTED, false));
        intro.addView(categoryPillStrip(categories));
        content.addView(intro);

        for (final Category category : categories) {
            LinearLayout p = panel();
            p.addView(categoryEditorHeader(category, countCardsInCategory(dayCards, category.name)));
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
            p.addView(colorPresetRow(color));
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
        add.addView(colorPresetRow(newColor));
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

    private HorizontalScrollView categoryPillStrip(List<Category> categories) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(0, dp(12), 0, dp(4));
        scroll.addView(strip, new HorizontalScrollView.LayoutParams(-2, dp(54)));
        for (Category category : categories) {
            TextView pill = categoryPill(category.name, category.colorHex, category.idle, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMargins(0, 0, dp(8), 0);
            strip.addView(pill, lp);
        }
        return scroll;
    }

    private LinearLayout categoryEditorHeader(Category category, int countToday) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(10));

        TextView swatch = new TextView(this);
        GradientDrawable swatchBg = new GradientDrawable();
        swatchBg.setColor(parseColor(category.colorHex, Colors.colorForCategory(category.name)));
        swatchBg.setCornerRadius(dp(8));
        swatchBg.setStroke(1, ColorUtils.withAlpha(Colors.TEXT, 42));
        swatch.setBackground(swatchBg);
        header.addView(swatch, new LinearLayout.LayoutParams(dp(28), dp(38)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(10), 0, 0, 0);
        copy.addView(text(category.name, 16, Colors.TEXT, false));
        String label = (category.system ? "System" : "Custom") + (category.idle ? " · Idle" : "") + " · " + countToday + " today";
        copy.addView(text(label, 11, Colors.MUTED, true));
        header.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        header.addView(categoryPill(category.name, category.colorHex, category.idle, true), new LinearLayout.LayoutParams(-2, dp(34)));
        return header;
    }

    private HorizontalScrollView colorPresetRow(final EditText target) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(0, dp(4), 0, dp(10));
        scroll.addView(strip, new HorizontalScrollView.LayoutParams(-2, dp(52)));
        for (final String hex : CATEGORY_COLOR_PRESETS) {
            TextView swatch = new TextView(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(parseColor(hex, Colors.ACCENT));
            bg.setStroke(2, Color.WHITE);
            swatch.setBackground(bg);
            swatch.setContentDescription("Use color " + hex);
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    target.setText(hex);
                    target.setSelection(target.getText().length());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.setMargins(0, 0, dp(10), 0);
            strip.addView(swatch, lp);
        }
        return scroll;
    }

    private TextView categoryPill(String name, String colorHex, boolean idle, boolean selected) {
        int color = parseColor(colorHex, Colors.colorForCategory(name));
        TextView pill = text("  " + clean(name, "Category") + "  ", 12, Colors.TEXT, false);
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                selected ? new int[]{0xfffffdf8, 0xffffe8d3} : new int[]{Color.WHITE, Color.WHITE});
        bg.setCornerRadius(dp(7));
        bg.setStroke(idle ? 2 : 1, selected ? 0xfffbbb80 : ColorUtils.withAlpha(color, idle ? 150 : 210));
        pill.setBackground(bg);
        pill.setCompoundDrawablesWithIntrinsicBounds(colorDot(color), null, null, null);
        pill.setCompoundDrawablePadding(dp(7));
        return pill;
    }

    private GradientDrawable colorDot(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setStroke(1, Color.WHITE);
        dot.setSize(dp(10), dp(10));
        return dot;
    }

    private void renderChat(DashboardMetrics metrics) {
        LinearLayout panel = panel();
        panel.addView(serif("Chat with your work journal", 28, Colors.TEXT));
        panel.addView(text("Ask about today, distractions, focus blocks, or where the time went. Dayflow will use your selected provider when available.", 14, Colors.MUTED, false));
        final EditText question = field("Where did my time go today?", "", false);
        panel.addView(question, new LinearLayout.LayoutParams(-1, dp(94)));

        LinearLayout contextRow = row();
        contextRow.addView(reviewChip("Tracked " + TimeUtil.shortDuration(metrics.trackedMs), Colors.STROKE, Colors.CARD_ALT), new LinearLayout.LayoutParams(0, dp(30), 1));
        contextRow.addView(reviewChip("Focus " + TimeUtil.shortDuration(metrics.productiveMs), 0xff42d0bb, ColorUtils.withAlpha(0xff42d0bb, 30)), new LinearLayout.LayoutParams(0, dp(30), 1));
        contextRow.addView(reviewChip("Distracted " + TimeUtil.shortDuration(metrics.distractionMs), 0xffff8772, ColorUtils.withAlpha(0xffff8772, 30)), new LinearLayout.LayoutParams(0, dp(30), 1));
        panel.addView(contextRow);

        LinearLayout prompts = row();
        prompts.addView(promptButton(question, "Standup"), new LinearLayout.LayoutParams(0, dp(38), 1));
        prompts.addView(promptButton(question, "Focus"), new LinearLayout.LayoutParams(0, dp(38), 1));
        prompts.addView(promptButton(question, "Distractions"), new LinearLayout.LayoutParams(0, dp(38), 1));
        panel.addView(prompts);

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
        if ("Profile".equals(section)) {
            renderSettingsProfile(panel);
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
        String[] sections = new String[]{"Profile", "Storage", "Privacy", "Providers", "Export", "Other"};
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
        if ("Profile".equals(section) || "Storage".equals(section) || "Privacy".equals(section)
                || "Providers".equals(section) || "Export".equals(section) || "Other".equals(section)) {
            return section;
        }
        return "Profile";
    }

    private void renderSettingsProfile(LinearLayout panel) {
        panel.addView(text("Profile", 13, Colors.MUTED, true));
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

    private void addHowItWorksCards(LinearLayout panel) {
        panel.addView(onboardingInfoCard("images/onboarding/how.png", "Install and forget", "Dayflow takes periodic Android screen captures and keeps them private on this device. Recording can be paused whenever you need."));
        panel.addView(onboardingInfoCard("images/onboarding/security.png", "Privacy by default", "Local heuristic and Ollama modes can run without sending screenshots to a cloud provider. Retention and blocked apps stay under your control."));
        panel.addView(onboardingInfoCard("images/onboarding/understanding.png", "Understand your day", "It separates focused work from drift, then turns the same history into timeline cards, reviews, daily notes, weekly charts, and chat context."));
    }

    private LinearLayout onboardingInfoCard(String assetPath, String title, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.TOP);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(92, 255, 255, 255));
        bg.setStroke(1, Color.argb(28, 0, 0, 0));
        bg.setCornerRadius(dp(6));
        card.setBackground(bg);

        ImageView icon = assetImage(assetPath);
        if (icon != null) {
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            card.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, 0, 0);
        copy.addView(text(title, 16, Colors.TEXT, false));
        copy.addView(text(body, 13, Colors.MUTED, false));
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        card.setLayoutParams(lp);
        return card;
    }

    private void addOnboardingPreview(LinearLayout panel, String assetPath, String title, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(150, 255, 255, 255));
        bg.setStroke(1, Color.argb(42, 0, 0, 0));
        bg.setCornerRadius(dp(10));
        card.setBackground(bg);

        ImageView image = assetImage(assetPath);
        if (image != null) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setAdjustViewBounds(false);
            card.addView(image, new LinearLayout.LayoutParams(-1, dp(155)));
        }
        card.addView(serif(title, 22, Colors.ACCENT));
        card.addView(text(body, 13, Colors.MUTED, false));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(12), 0, dp(4));
        panel.addView(card, lp);
    }

    private ImageView assetImage(String assetPath) {
        try (InputStream stream = getAssets().open(assetPath)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) return null;
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setContentDescription(assetPath);
            return image;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void renderCardList(List<TimelineCard> cards) {
        for (final TimelineCard card : cards) {
            content.addView(timelineActivityCard(card));
            addGap(10);
        }
    }

    private LinearLayout timelineActivityCard(final TimelineCard card) {
        LinearLayout p = panel();
        LinearLayout meta = row();
        meta.addView(reviewChip(TimeUtil.timeLabel(card.startMs) + " - " + TimeUtil.timeLabel(card.endMs), Colors.STROKE, Colors.CARD_ALT), new LinearLayout.LayoutParams(0, dp(30), 1));
        meta.addView(reviewChip(clean(card.category, "Uncategorized"), Colors.colorForCategory(card.category), ColorUtils.withAlpha(Colors.colorForCategory(card.category), 28)), new LinearLayout.LayoutParams(0, dp(30), 1));
        p.addView(meta);

        TextView title = serif(card.title, 24, Colors.TEXT);
        p.addView(title);

        addTimelinePreview(p, card);

        p.addView(text("SUMMARY", 12, Colors.MUTED, true));
        p.addView(text(card.summary == null ? "" : card.summary, 14, Colors.TEXT, false));
        if (card.detailedSummary != null && !card.detailedSummary.trim().isEmpty() && !card.detailedSummary.trim().equals(card.summary == null ? "" : card.summary.trim())) {
            p.addView(text("DETAILED SUMMARY", 12, Colors.MUTED, true));
            p.addView(text(formatDetailedSummary(card.detailedSummary), 13, Colors.TEXT, false));
        }

        p.addView(timelineSummaryFeedback(card));
        p.addView(cardActions(card));
        return p;
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
        final List<Category> categories = orderedCategoriesForPicker(db.fetchCategories(), card.category);
        if (categories.isEmpty()) {
            setStatus("No categories available.");
            return;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(14), dp(14), dp(14), dp(8));
        scroll.addView(sheet);
        sheet.addView(serif("Move card", 24, Colors.TEXT));
        sheet.addView(text(TimeUtil.timeLabel(card.startMs) + " - " + TimeUtil.timeLabel(card.endMs) + "\n" + card.title, 13, Colors.MUTED, false));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        for (final Category category : categories) {
            sheet.addView(categoryChoiceRow(card, category, normalized(category.name).equals(normalized(card.category)), dialogRef));
        }

        Button edit = smallButton("Edit category descriptions");
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                selectedTab = "Categories";
                refresh();
            }
        });
        sheet.addView(edit, new LinearLayout.LayoutParams(-1, dp(42)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Move card to category")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    private View categoryChoiceRow(final TimelineCard card, final Category category, boolean selected, final AlertDialog[] dialogRef) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                selected ? new int[]{0xfffffdf8, 0xffffe8d3} : new int[]{0xffffffff, 0xfffefefe});
        int color = parseColor(category.colorHex, Colors.colorForCategory(category.name));
        bg.setCornerRadius(dp(8));
        bg.setStroke(selected ? 2 : 1, selected ? 0xfffbbb80 : ColorUtils.withAlpha(color, 120));
        row.setBackground(bg);

        TextView swatch = new TextView(this);
        GradientDrawable swatchBg = new GradientDrawable();
        swatchBg.setShape(GradientDrawable.OVAL);
        swatchBg.setColor(color);
        swatchBg.setStroke(1, Color.WHITE);
        swatch.setBackground(swatchBg);
        row.addView(swatch, new LinearLayout.LayoutParams(dp(14), dp(14)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(10), 0, 0, 0);
        copy.addView(text(category.name, 14, Colors.TEXT, false));
        String detail = clean(category.details, "Add details so Dayflow can classify this more accurately.");
        copy.addView(text(shortText(detail, 90), 11, Colors.MUTED, false));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        if (selected) row.addView(text("Selected", 11, Colors.ACCENT, true), new LinearLayout.LayoutParams(-2, -2));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                db.updateTimelineCardCategory(card.id, category.name);
                setStatus("Card moved to " + category.name + ".");
                refresh();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(lp);
        return row;
    }

    private List<Category> orderedCategoriesForPicker(List<Category> categories, String currentCategory) {
        List<Category> ordered = new ArrayList<>();
        String current = normalized(currentCategory);
        for (Category category : categories) {
            if (normalized(category.name).equals(current)) ordered.add(category);
        }
        for (Category category : categories) {
            if (!normalized(category.name).equals(current)) ordered.add(category);
        }
        return ordered;
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

    private Button promptButton(final EditText question, String label) {
        Button button = smallButton(label);
        final String prompt;
        if ("Standup".equals(label)) prompt = "What should I mention in standup today?";
        else if ("Focus".equals(label)) prompt = "Where did my focused time go today?";
        else prompt = "What distracted me most today?";
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                question.setText(prompt);
                question.setSelection(question.getText().length());
            }
        });
        return button;
    }

    private Button cardsToReviewButton(int count) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(count + (count == 1 ? " card to review" : " cards to review"));
        button.setTextColor(Color.WHITE);
        button.setTypeface(DayflowType.sans(this, true));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xffff9a70, 0xffbdaeec});
        bg.setStroke(dp(1), 0xffffd9d2);
        bg.setCornerRadius(dp(22));
        button.setBackground(bg);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                selectedTab = "Review";
                refresh();
            }
        });
        return button;
    }

    private void addTimelinePreview(LinearLayout panel, final TimelineCard card) {
        List<ScreenshotRecord> frames = db.screenshotsInRange(card.startMs, card.endMs, 12);
        if (frames.isEmpty()) return;
        ScreenshotRecord frame = frames.get(frames.size() / 2);
        Bitmap bitmap = previewBitmap(frame.filePath);
        if (bitmap == null) return;
        FrameLayout preview = new FrameLayout(this);
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription("Timeline screenshot preview");
        preview.addView(image, new FrameLayout.LayoutParams(-1, -1));

        TextView play = text("Play timelapse", 12, Color.WHITE, true);
        play.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x99000000);
        pill.setCornerRadius(dp(22));
        play.setBackground(pill);
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(dp(128), dp(40), Gravity.CENTER);
        preview.addView(play, playLp);
        preview.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                openOrGenerateTimelapse(card, true);
            }
        });

        GradientDrawable border = new GradientDrawable();
        border.setColor(Colors.CARD_ALT);
        border.setStroke(1, Colors.STROKE);
        border.setCornerRadius(dp(12));
        preview.setBackground(border);
        preview.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(190));
        lp.setMargins(0, dp(10), 0, dp(12));
        panel.addView(preview, lp);
    }

    private LinearLayout timelineSummaryFeedback(final TimelineCard card) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xfffafafa);
        bg.setStroke(1, 0xffeeeeee);
        bg.setCornerRadius(dp(1));
        bar.setBackground(bg);

        Button delete = smallButton("Delete");
        delete.setTextColor(0xffc05c54);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                confirmDeleteCard(card);
            }
        });
        bar.addView(delete, new LinearLayout.LayoutParams(dp(86), dp(32)));

        TextView label = text("Rate this summary", 12, Colors.MUTED, false);
        label.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        bar.addView(label, new LinearLayout.LayoutParams(0, dp(32), 1));

        Button up = smallButton("Good");
        up.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { setStatus("Summary feedback saved."); }
        });
        Button down = smallButton("Off");
        down.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { setStatus("Summary feedback saved. You can reprocess this day from Settings."); }
        });
        bar.addView(up, new LinearLayout.LayoutParams(dp(72), dp(32)));
        bar.addView(down, new LinearLayout.LayoutParams(dp(66), dp(32)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
        lp.setMargins(0, dp(12), 0, dp(8));
        bar.setLayoutParams(lp);
        return bar;
    }

    private LinearLayout chatMessageView(final DayflowChatMessage message) {
        boolean user = "user".equals(message.role);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams outerLp = new LinearLayout.LayoutParams(-1, -2);
        outerLp.setMargins(0, 0, 0, dp(12));
        outer.setLayoutParams(outerLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout bubble = chatBubble(user);
        if (user) {
            bubble.addView(chatText(message.content, 13, Color.WHITE, true));
        } else {
            addChatMarkdownBlocks(bubble, message.content);
        }

        Space spacer = new Space(this);
        if (user) {
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            row.addView(bubble, new LinearLayout.LayoutParams(0, -2, 4));
        } else {
            row.addView(bubble, new LinearLayout.LayoutParams(0, -2, 4));
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        }
        outer.addView(row);

        if (!user) outer.addView(chatFeedbackRow(message));
        return outer;
    }

    private LinearLayout chatBubble(boolean user) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(user ? 0xfff98d3d : Color.WHITE);
        bg.setStroke(user ? 0 : 1, user ? 0xfff98d3d : 0xffe8e8e8);
        bg.setCornerRadius(dp(16));
        bubble.setBackground(bg);
        return bubble;
    }

    private TextView chatText(String value, int sp, int color, boolean bold) {
        TextView view = text(value == null ? "" : value, sp, color, false);
        view.setTypeface(DayflowType.sans(this, bold));
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private void addChatMarkdownBlocks(LinearLayout parent, String content) {
        String[] lines = (content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n')).split("\n", -1);
        List<String> paragraph = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushChatParagraph(parent, paragraph);
                continue;
            }
            if (trimmed.startsWith("```")) {
                flushChatParagraph(parent, paragraph);
                String language = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i]);
                    i++;
                }
                addChatCodeBlock(parent, language, code.toString());
                continue;
            }
            if (trimmed.startsWith("#")) {
                flushChatParagraph(parent, paragraph);
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                String heading = trimmed.substring(level).trim();
                if (!heading.isEmpty()) addChatHeading(parent, heading, level);
                continue;
            }
            if (trimmed.startsWith(">")) {
                flushChatParagraph(parent, paragraph);
                addChatQuote(parent, trimmed.substring(1).trim());
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || isOrderedListLine(trimmed)) {
                flushChatParagraph(parent, paragraph);
                addChatListItem(parent, listMarker(trimmed), listContent(trimmed));
                continue;
            }
            paragraph.add(line);
        }
        flushChatParagraph(parent, paragraph);
    }

    private void flushChatParagraph(LinearLayout parent, List<String> paragraph) {
        if (paragraph.isEmpty()) return;
        String text = joinLines(paragraph).trim();
        paragraph.clear();
        if (text.isEmpty()) return;
        TextView view = chatText(text, 13, Colors.TEXT, true);
        addChatBlock(parent, view);
    }

    private void addChatHeading(LinearLayout parent, String heading, int level) {
        addChatBlock(parent, chatText(heading, level <= 1 ? 17 : 15, Colors.TEXT, true));
    }

    private void addChatListItem(LinearLayout parent, String marker, String content) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        TextView bullet = chatText(marker, 13, Colors.TEXT, true);
        bullet.setGravity(Gravity.RIGHT);
        row.addView(bullet, new LinearLayout.LayoutParams(dp(22), -2));
        TextView body = chatText(content, 13, Colors.TEXT, true);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(0, -2, 1);
        bodyLp.setMargins(dp(8), 0, 0, 0);
        row.addView(body, bodyLp);
        addChatBlock(parent, row);
    }

    private void addChatQuote(LinearLayout parent, String quote) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View bar = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xffe7d7c6);
        bg.setCornerRadius(dp(2));
        bar.setBackground(bg);
        row.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(44)));
        TextView body = chatText(quote, 13, 0xff5a5147, true);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(0, -2, 1);
        bodyLp.setMargins(dp(10), 0, 0, 0);
        row.addView(body, bodyLp);
        addChatBlock(parent, row);
    }

    private void addChatCodeBlock(LinearLayout parent, String language, String code) {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xfffaf7f2);
        bg.setStroke(1, 0xffe7ddd2);
        bg.setCornerRadius(dp(12));
        shell.setBackground(bg);
        if (language != null && !language.trim().isEmpty()) {
            shell.addView(chatText(language.trim().toUpperCase(Locale.US), 10, 0xff9a7c60, true));
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        TextView codeView = chatText(code, 12, Colors.TEXT, false);
        codeView.setTypeface(Typeface.MONOSPACE);
        scroll.addView(codeView, new HorizontalScrollView.LayoutParams(-2, -2));
        shell.addView(scroll);
        addChatBlock(parent, shell);
    }

    private void addChatBlock(LinearLayout parent, View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(8));
        parent.addView(view, lp);
    }

    private LinearLayout chatFeedbackRow(final DayflowChatMessage message) {
        LinearLayout row = row();
        row.setPadding(dp(10), dp(2), 0, 0);
        Button copy = smallButton("Copy");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                copyText("Dayflow answer", message.content);
            }
        });
        Button good = smallButton("Useful");
        good.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { setStatus("Feedback saved. Thanks."); }
        });
        Button fix = smallButton("Needs work");
        fix.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { setStatus("Feedback saved. Dayflow will keep the context."); }
        });
        row.addView(copy, new LinearLayout.LayoutParams(dp(82), dp(34)));
        row.addView(good, new LinearLayout.LayoutParams(dp(92), dp(34)));
        row.addView(fix, new LinearLayout.LayoutParams(dp(112), dp(34)));
        return row;
    }

    private Button ratingButton(final TimelineCard card, final String rating, boolean selected) {
        Button button = selected ? pillButton(rating) : smallButton(rating);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                rateReviewCard(card, rating);
            }
        });
        return button;
    }

    private void rateReviewCard(TimelineCard card, String rating) {
        db.saveReviewRating(card, rating);
        setStatus("Marked " + rating.toLowerCase(Locale.US) + ".");
        refresh();
    }

    private void attachReviewSwipe(View view, final TimelineCard card) {
        final float[] downX = new float[1];
        final float[] downY = new float[1];
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View touched, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX[0] = event.getX();
                        downY[0] = event.getY();
                        return false;
                    case MotionEvent.ACTION_UP:
                        float dx = event.getX() - downX[0];
                        float dy = event.getY() - downY[0];
                        float absX = Math.abs(dx);
                        float absY = Math.abs(dy);
                        if (absX > dp(72) && absX > absY * 1.2f) {
                            rateReviewCard(card, dx > 0 ? "Focused" : "Distracted");
                            return true;
                        }
                        if (-dy > dp(72) && absY > absX * 1.1f) {
                            rateReviewCard(card, "Neutral");
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    private String reviewSubtitle(ReviewSnapshot snapshot) {
        String base = snapshot.hasData()
                ? "Last reviewed at " + TimeUtil.timeLabel(snapshot.lastReviewedAtMs) + "."
                : "No reviews yet.";
        if (snapshot.totalCards <= 0) return base;
        String count = snapshot.unreviewedCards == 1 ? "1 card" : snapshot.unreviewedCards + " cards";
        if (snapshot.unreviewedCards > 0) {
            return base + " Review " + count + " to update your data.";
        }
        return base + " All " + snapshot.totalCards + " cards are reviewed.";
    }

    private TextView reviewChip(String label, int strokeColor, int fillColor) {
        TextView chip = text(label, 11, Colors.TEXT, false);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setStroke(1, strokeColor);
        bg.setCornerRadius(dp(7));
        chip.setBackground(bg);
        return chip;
    }

    private TextView reviewRatingChip(String label, String rating) {
        int color = reviewRatingColor(rating);
        TextView chip = reviewChip(label, color, ColorUtils.withAlpha(color, 32));
        chip.setTextColor(color);
        return chip;
    }

    private int reviewRatingColor(String rating) {
        String normalized = rating == null ? "" : rating.toLowerCase(Locale.US);
        if (normalized.contains("distract")) return 0xffff8772;
        if (normalized.contains("focus")) return 0xff42d0bb;
        return Colors.IDLE;
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

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String formatDetailedSummary(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.contains("\n") || clean.contains("\r")) return clean;
        return clean.replaceAll("(?<!^)(\\b\\d{1,2}:\\d{2}\\s?(?:AM|PM)\\s*-\\s*\\d{1,2}:\\d{2}\\s?(?:AM|PM)\\b)", "\n$1");
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    private static boolean isOrderedListLine(String trimmed) {
        int index = 0;
        while (index < trimmed.length() && Character.isDigit(trimmed.charAt(index))) index++;
        return index > 0
                && index + 1 < trimmed.length()
                && (trimmed.charAt(index) == '.' || trimmed.charAt(index) == ')')
                && Character.isWhitespace(trimmed.charAt(index + 1));
    }

    private static String listMarker(String trimmed) {
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) return "*";
        int index = 0;
        while (index < trimmed.length() && Character.isDigit(trimmed.charAt(index))) index++;
        if (index < trimmed.length()) return trimmed.substring(0, index + 1);
        return "*";
    }

    private static String listContent(String trimmed) {
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) return trimmed.substring(2).trim();
        int index = 0;
        while (index < trimmed.length() && Character.isDigit(trimmed.charAt(index))) index++;
        if (index + 1 < trimmed.length()) return trimmed.substring(index + 1).trim();
        return trimmed;
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

    private static final class StandupDraft {
        String highlights = "";
        String tasks = "";
        String blockers = "";

        boolean isEmpty() {
            return highlights.trim().isEmpty() && tasks.trim().isEmpty() && blockers.trim().isEmpty();
        }
    }
}
