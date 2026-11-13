# PHOTON AI NEWS — Android app (v1.0.0 source build)

A Russian-language Android RSS reader for significant AI developments. The app uses a black/white editorial interface, local news history, manual refresh, and a 24-hour background sync.

## Implemented in this source version

- Native Android app: Kotlin + Jetpack Compose; min SDK 26.
- Daily background fetch scheduled with WorkManager (24-hour periodic interval; Android may defer execution under Doze/battery restrictions).
- RSS sources: OpenAI, Google AI, TechCrunch AI, Hugging Face, MIT Technology Review.
- Deduplication by SHA-256 of canonical article URL.
- SQLite local archive; entries older than 30 days are pruned.
- Relevance keyword heuristic for launches, releases, research, safety, business and regulation; suppresses obvious opinion/promotional/how-to items. Items older than 10 days are excluded.
- On-device English-to-Russian title and summary translation using Google ML Kit, with English fallback.
- Android notifications for newly discovered qualifying items. Android 13+ requires notification permission.
- Manual refresh button in the app.

## Limitations

1. RSS endpoint availability and feed formats must be validated on physical devices; publishers can change feed URLs or markup.
2. Significance ranking is a deterministic keyword heuristic, not semantic LLM analysis or independent fact-checking. Production editorial quality should add event clustering, semantic ranking, source cross-checking and factual verification.
3. If the ML Kit translation model cannot be downloaded, original English titles/summaries remain visible. Full articles open at the publisher and may remain in the publisher's language.
4. Background work is periodic and best-effort. Android does not guarantee execution at an exact wall-clock time every 24 hours.
5. This repository currently builds a debug APK, not a signed production release.

## Build

Requirements: JDK 17, Android SDK Platform 35, Android Build Tools, Gradle 8.9.

```bash
gradle --no-daemon :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds the debug APK on every push to `main` or `master` and stores it as the `photon-ai-news-debug-apk` workflow artifact.
