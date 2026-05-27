# Dayflow Research Notes

## Product Principle

Dayflow is not a timer. It is an automatic work journal:

1. Capture screen context without manual logging.
2. Store raw evidence locally.
3. Batch recent captures.
4. Ask an AI provider to describe what actually happened.
5. Convert descriptions into chronological activity cards.
6. Aggregate the cards into daily standup, weekly analytics, distraction review, export, and chat.

## Source Architecture Observed

The upstream macOS app is SwiftUI and local-first. Its current source uses `SCScreenshotManager` for periodic screenshots instead of a continuous video stream. The default source-level screenshot interval is 10 seconds, while the public copy describes 1 FPS. The durable product behavior is still the same idea: lightweight screen sampling, then batch analysis.

Core pipeline:

- `ScreenRecorder`: state machine: idle, starting, capturing, paused.
- `StorageManager`: local Application Support folder plus SQLite.
- `screenshots`: captured image rows.
- `analysis_batches`: analysis windows.
- `batch_screenshots`: screenshots grouped into batches.
- `observations`: AI transcription/observation output.
- `timeline_cards`: final visible activity cards.
- `AnalysisManager`: checks every minute, builds completed batches from the last 24 hours.
- `LLMService`: provider router for Dayflow backend, Gemini, Ollama, and chat CLI providers.
- `BatchingConfig`: 15-minute target batches, 2-minute max capture gap, 45-minute card lookback.
- Idle shortcut: long mostly-idle windows can skip LLM and become an Idle card.

## Android Mapping

macOS `SCScreenshotManager` maps to Android `MediaProjection` plus a foreground service.

Android constraints:

- User consent is required for every capture session.
- Android 14 requires `foregroundServiceType="mediaProjection"` and `FOREGROUND_SERVICE_MEDIA_PROJECTION`.
- A projection token is used for a single virtual display session.
- Usage Access is user-enabled through Settings and is needed for app-level foreground context.

Implemented Android equivalent:

- `CaptureService`: foreground MediaProjection service, screenshot loop, local JPEG storage.
- `ForegroundAppReader`: reads recent UsageStats events for foreground app label/package.
- `DayflowDatabase`: local SQLite schema modeled after Dayflow tables.
- `AnalysisEngine`: 24-hour lookback, 15-minute batch target, 2-minute split gap, 45-minute replacement window.
- `HybridActivityAnalyzer`: Gemini vision when configured; otherwise local heuristic cards.
- `MainActivity`: Dayflow-like Timeline, Daily, Weekly, Chat, and Settings surfaces.

## What To Improve Next

- Add Android AccessibilityService as a second signal for window titles and text snippets.
- Add a real local multimodal model path through an on-device runtime or LAN Ollama endpoint.
- Add encrypted screenshot storage and retention controls.
- Add timelapse playback from captured screenshots.
- Add timeline card review/rating gestures.
- Add category editor UI matching upstream role presets.
