# PHOTON AI NEWS — Android app

A Russian-language Android news reader focused on significant AI developments. The app uses a restrained black-and-white editorial interface and an offline-first archive.

## Current capabilities

- Native Android application built with Kotlin and Jetpack Compose; min SDK 26.
- Concurrent RSS/Atom collection from OpenAI, Google AI, TechCrunch AI, Hugging Face and MIT Technology Review.
- Conservative relevance scoring, freshness filtering, canonical URL deduplication and near-duplicate headline suppression.
- Deterministic editorial categories: Products, Research, Business, Safety, Regulation and Other.
- On-device English-to-Russian translation with source-language fallback; no paid AI/API service is called.
- SQLite archive with schema migration, indexed category/date/bookmark fields and retention of saved stories.
- In-app search, category filters, relevance/newest sorting and persistent bookmarks.
- Initial background sync plus best-effort 24-hour WorkManager refresh.
- Notifications for newly discovered qualifying stories, subject to Android notification permission.
- Manual refresh and direct links to publisher sources.

## Editorial and technical boundaries

- Significance ranking and category assignment are deterministic heuristics, not semantic LLM analysis or independent fact-checking.
- Feed availability, publisher markup and publication-date formats can change. RSS results must be monitored against real devices and live feeds.
- Translation runs on-device after the ML Kit model is available. Original English text is retained when translation is unavailable or blank.
- WorkManager periodic execution is best-effort; Android may defer it under Doze and battery restrictions.
- Full articles open on publisher websites and may remain in their original language.
- GitHub Actions currently builds a debug APK; this is not a signed production release.

## Build

Requirements: JDK 17, Android SDK Platform 35, Android Build Tools, Gradle 8.9.

```bash
gradle --no-daemon :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds the debug APK on branch pushes and pull requests targeting main/master. The artifact is named `photon-ai-news-debug-apk`.
