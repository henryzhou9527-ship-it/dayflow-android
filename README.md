# Dayflow Android

An Android-native Dayflow prototype: private screen journal, local-first storage, automatic 15-minute activity batches, Dayflow-inspired timeline/daily/weekly/journal/review/chat views, editable categories, privacy blocking, retention controls, and configurable AI provider analysis.

## What It Does

- Captures periodic screenshots through Android MediaProjection.
- Stores timeline data locally in SQLite and encrypts newly captured screenshot files with an Android Keystore key.
- Uses Usage Access metadata to identify the foreground app.
- Can optionally use an Android AccessibilityService as a local-only second signal for window titles and short visible-text snippets, improving analysis without sign-in.
- Tracks recording health locally, including service heartbeat, last successful frame, capture errors, active resolution, and restart prompts when the recorder needs attention.
- Verifies encrypted screenshot storage before starting capture and exposes a manual storage test in Settings.
- Builds 15-minute analysis batches and rewrites cards with a 45-minute lookback window.
- Skips AI calls for long launcher/lock-screen idle batches and writes an Idle card directly, matching Dayflow's lightweight idle shortcut behavior.
- Supports Custom API endpoints with OpenAI-compatible chat completions, Gemini vision analysis, heuristic fallback, and Ollama vision models such as `qwen3-vl:4b`.
- Chat uses saved timeline cards, journal notes, category totals, and recent chat history, then answers through Custom API, Gemini, or Ollama when configured.
- Chat renders Dayflow-style user/assistant bubbles, context chips, suggested prompts, markdown blocks, code blocks, copy actions, and lightweight feedback controls.
- Provider settings include a backup provider so timeline analysis and chat can retry through another route before falling back locally.
- Provider setup includes Android-native connection tests for Custom API, Gemini, and Ollama; the Custom API test sends a tiny vision payload so the selected endpoint proves it can read screenshots before timeline analysis depends on it.
- Local diagnostics record provider attempts, fallback paths, batch decisions, errors, latency, and recent timeline state, with a copyable report in Settings and a dismissible in-app notice when analysis needs attention.
- AI timeline parsing accepts plain JSON arrays, fenced JSON, and common `{ "cards": [...] }` wrappers so Custom API and local model outputs are less brittle.
- Settings are organized into Dayflow-like Profile, Storage, Privacy, Providers, Export, and Other sections.
- Other settings include app preference toggles and an output-language override that is passed into timeline analysis and chat prompts.
- Adds first-run onboarding with Dayflow source preview assets, journal entries, daily goals, Dayflow-style review ratings, editable categories, color preset swatches, storage stats, per-app privacy blocking, day deletion, and reprocessing controls.
- Daily goals include a native review/setup flow with yesterday's goal review, last-week averages, focus/distraction category assignment, skip, and persistence.
- Daily includes the original-style data readiness gate: 5 hours of analyzed timeline duration, progress text, permission/recording/analyze actions, provider selection, and persisted unlock.
- Timeline and review cards support manual category reassignment with Dayflow-style category pills, single-card deletion, persisted summary feedback, and Dayflow-style cards-to-review prompts.
- Timeline includes Day/Week mode switching, native date picking, quick copy/export actions, and week-level clipboard/export output that mirror Dayflow's timeline workflow while staying touch-first on Android.
- Timeline cards include category/time pills, screenshot preview thumbnails, SUMMARY/DETAILED SUMMARY sections, and tap-to-play timelapse entry points.
- Timeline week mode renders a seven-day 4 AM-to-4 AM grid with tappable activity cards and day columns, giving the original week timeline behavior an Android-native inspector flow.
- Timeline and review cards can generate, save, regenerate, and play MP4 timelapse summaries from the card's captured screenshots.
- Daily view surfaces day-goal progress, focus summaries, distraction summaries, an original-style touch workflow grid with dynamic time windows, totals, stats, distraction markers, and editable Dayflow-style standup cards for highlights, tasks, and blockers.
- Daily standups are stored by day and can be saved or regenerated through the selected provider with fallback.
- Journal reminders can schedule recurring intention and reflection notifications on selected weekdays, with boot-time rescheduling.
- Daily and Weekly ready notifications mirror Dayflow's return-to-review flow, opening directly into the relevant Android tab when enough analyzed history exists.
- Journal uses an original-style two-page board for intentions, notes, long-term goals, reflections, and Dayflow-generated daily summaries.
- Redacts screenshots for blocked apps while still preserving timeline continuity.
- New screenshot files are encrypted at rest; older plaintext captures remain readable for analysis, previews, and timelapses.
- Recording/privacy settings include per-app blocking, optional window-context capture, Dayflow/Fast/Battery cadence presets, batch size, max gap, and card lookback controls.
- Supports timed/indefinite recording pauses and shows saved screenshots plus playable timelapses inside review cards for frame-by-frame context.
- Review includes Dayflow's focused/neutral/distracted summary bars, cards-to-review progress, last-reviewed state, undo, current-rating chips, and Android-native left/up/right swipe rating.
- Review cards include an Android-native touch scrubber that mirrors Dayflow's media review behavior with tap-to-play and drag-to-seek interactions.
- Weekly view supports Monday-based week navigation, weekly distribution donuts, focus heatmaps, weekly overview footer metrics, weekly workflow grids, time distribution, context-shift comparison charts, top highlights, visual 1:1 suggestions, application interaction summaries, touch treemaps, category-to-app Sankey breakdowns, and app-switching network charts.
- Onboarding stays local/function-focused while matching Dayflow's visual setup flow with how-it-works cards, timeline/journal/weekly previews, category pills, permissions, and analysis setup.
- Exports any date range as Markdown through Android's document picker, with clipboard fallback.
- Reprocesses a specific timeline day by clearing old cards/observations and re-analyzing the original saved batches.
- Keeps the Dayflow visual language while adapting interactions to Android: bundled Dayflow fonts/assets, warm cream/orange gradients, serif headings, soft white panels, compact timeline cards, touch-first review controls, heatmaps, and productivity dashboards.

## APK

Debug APK:

```sh
/Users/henry/Claude/dayflow-android/app/build/outputs/apk/debug/app-debug.apk
```

## Build

This workspace has a local JDK and Android SDK installed under:

```sh
/Users/henry/.cache/dayflow-build/
```

Build command:

```sh
cd /Users/henry/Claude/dayflow-android
JAVA_HOME=/Users/henry/.cache/dayflow-build/jdk/Contents/Home \
ANDROID_HOME=/Users/henry/.cache/dayflow-build/android-sdk \
./gradlew assembleDebug
```

Verification:

```sh
JAVA_HOME=/Users/henry/.cache/dayflow-build/jdk/Contents/Home \
ANDROID_HOME=/Users/henry/.cache/dayflow-build/android-sdk \
./gradlew assembleDebug lintDebug
```

## First Run

1. Install the APK on an Android device.
2. Open Dayflow and follow the first-run setup: welcome, role, preferences, AI provider, categories, permissions, completion.
3. Enable Usage Access when prompted so foreground app labels are available.
4. Approve the Android screen-capture prompt.
5. Dayflow creates an `Installed Dayflow!` sample card immediately, then real timeline cards appear after a complete analysis batch.
6. Add a Custom API endpoint/model/key during setup, or choose another provider/fallback and rerun setup later from `Settings`.

## Current Limits

- Android requires a fresh user-approved MediaProjection session; recording cannot silently resume after reboot.
- The default analyzer is metadata-based. Custom API and Gemini vision can inspect sampled screenshots when configured.
- No physical device was connected in this workspace, so verification covered build and lint, not live capture.
