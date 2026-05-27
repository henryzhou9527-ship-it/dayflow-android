# Dayflow Android

An Android-native Dayflow prototype: private screen journal, local-first storage, automatic 15-minute activity batches, Dayflow-inspired timeline/daily/weekly/journal/review/chat views, editable categories, privacy blocking, retention controls, and optional Gemini vision analysis.

## What It Does

- Captures periodic screenshots through Android MediaProjection.
- Stores screenshots and timeline data locally in SQLite.
- Uses Usage Access metadata to identify the foreground app.
- Builds 15-minute analysis batches and rewrites cards with a 45-minute lookback window.
- Supports heuristic analysis, Gemini vision analysis, and Ollama local vision models such as `qwen3-vl:4b`.
- Adds journal entries, daily goals, review ratings, editable categories, storage stats, per-app privacy blocking, day deletion, and reprocessing controls.
- Redacts screenshots for blocked apps while still preserving timeline continuity.
- Supports timed/indefinite recording pauses and shows saved screenshots inside review cards for frame-by-frame context.
- Exports the current day timeline as Markdown.
- Keeps the Dayflow visual language: bundled Dayflow fonts/assets, warm cream/orange gradients, serif headings, soft white panels, compact timeline cards, heatmaps, and productivity dashboards.

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
2. Open Dayflow and tap `Start`.
3. Approve the Android screen-capture prompt.
4. Open `Settings` and enable Usage Access for Dayflow so foreground app labels are available.
5. Leave it running. Timeline cards appear after a complete 15-minute batch.
6. Optional: add a Gemini API key in Settings and enable Gemini vision analysis.

## Current Limits

- Android requires a fresh user-approved MediaProjection session; recording cannot silently resume after reboot.
- The default analyzer is metadata-based. Gemini vision can inspect sampled screenshots when configured.
- No physical device was connected in this workspace, so verification covered build and lint, not live capture.
