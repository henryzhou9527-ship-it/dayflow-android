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
- `LLMService`: provider router in the source; the Android clone prioritizes Custom API, Gemini, Ollama, and heuristic routes instead of hosted-only services.
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

- `CaptureService`: foreground MediaProjection service, screenshot loop, Android Keystore-encrypted local screenshot storage.
- `CaptureService`: checks encrypted screenshot storage before opening a capture session, so storage failures surface before a zero-frame recording run.
- `ForegroundAppReader`: reads recent UsageStats events for foreground app label/package.
- `DayflowDatabase`: local SQLite schema modeled after Dayflow tables.
- `AnalysisEngine`: 24-hour lookback, 15-minute batch target, 2-minute split gap, 45-minute replacement window.
- `AnalysisEngine`: explicit launcher/lock-screen idle shortcut that writes an Idle card without spending an AI call.
- `ScreenshotStorage`: encrypted-at-rest capture files with compatibility readers for older plaintext screenshots.
- `HybridActivityAnalyzer`: Custom API or Gemini vision when configured; otherwise local heuristic cards.
- `MainActivity`: Dayflow-like Timeline, Daily, Weekly, Chat, and Settings surfaces.
- `ProviderConnectionTester`: verifies Custom API with an OpenAI-compatible image message, not just a text ping.

## What To Improve Next

- Broaden Custom API compatibility checks across common OpenAI-compatible providers and model families.
- Add deeper mobile QA on a physical Android device for capture continuity, notification deep links, and long-run battery behavior.
