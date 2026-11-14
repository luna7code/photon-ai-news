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
- Event Intelligence groups similar headlines across publishers while retaining separate source articles.
- Breaking AI highlights recent high-impact announcements using transparent deterministic rules.
- Source Evidence shows the number and names of publishers covering an event, with direct links to each report.
- Semantic Intelligence improves event matching with normalized multilingual token overlap and morphology-aware matching; it is a local heuristic, not an embedding model.
- Source Trust Engine reports provenance transparency signals (HTTPS links, host diversity, dates and publisher metadata), not publisher honesty or factual truth.
- Fact Cross-Check flags comparable numeric discrepancies between reports for human review; it does not determine which claim is correct.
- Global Breaking Monitor elevates recent, high-impact events using freshness, impact and corroboration thresholds.
- AI Impact Ranking orders events using transparent significance, coverage, freshness and topic weights.
- Initial background sync plus best-effort 24-hour WorkManager refresh.
- Notifications for newly discovered qualifying stories, subject to Android notification permission.
- Manual refresh and direct links to publisher sources.

## Editorial and technical boundaries

- Event clustering, breaking detection, significance ranking and category assignment use deterministic heuristics; they are not LLM reasoning or independent fact-checking.
- Publisher count is an evidence signal only. It does not establish editorial independence, source quality, truth, or factual verification. Readers must inspect the linked original reports.
- Semantic matching, source transparency scoring, numeric discrepancy detection, impact ranking and breaking-event selection are deterministic heuristics. They do not use a hosted LLM, embeddings, independent source reputation database or automated fact verification.
- Source Trust Engine measures provenance completeness only; a high score is not an endorsement of a publisher. Fact Cross-Check flags possible differences and never decides which report is correct.
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

## PHOTON AI NEWS v3.1 — neural semantic and editorial layer

- Optional **multilingual-e5-small** neural sentence embeddings run locally through ONNX Runtime Android. The model supports 94 languages and emits 384-dimensional sentence representations; the app uses cosine similarity for cross-language event clustering and duplicate reduction.
- The model is **opt-in** and is not bundled into the APK. The user explicitly starts a one-time download (~118 MB quantized weights plus tokenizer). Files are pinned to immutable Hugging Face revisions and SHA-256 verified before installation. If model download or initialization fails, the app retains the deterministic lexical fallback.
- The current ONNX weight file is the official int8 quantized export from `intfloat/multilingual-e5-small`; inference uses the ONNX Runtime CPU provider. The filename contains an AVX512/VNNI optimization label; actual ARM-device performance and kernel fallback must be benchmarked on physical devices before production rollout.
- **Editorial Composer** creates a source-grounded extractive draft: it ranks a representative source, deduplicates repeated summaries, preserves direct links and lists editorial caveats. It does not invent facts or claim to be an LLM-generated article.
- Event analysis and editorial draft composition run on background dispatchers so model inference does not block the Compose UI thread.

## v3 editorial audit — corrections and known limits

- The previous semantic matcher was lexical Jaccard/containment with suffix stripping, not neural semantic understanding. It remains only as a fallback; the optional E5 model supplies actual multilingual embeddings when installed.
- The previous Source Trust score could assign a single-source event an unjustifiably high value. The revised score measures provenance completeness only and caps single-host clusters at 64/100. It must never be interpreted as truth, publisher reputation, or editorial independence.
- Fact Cross-Check is a heuristic numeric-discrepancy detector. It ignores calendar-year values, requires matching units and at least two shared context terms, and flags candidates for human review. It does not adjudicate claims or normalize every currency/unit expression.
- Breaking classification no longer treats ordinary launches, announcements, acquisitions, or large monetary values as urgent signals. It requires critical incident terms and freshness. Global Breaking additionally requires at least two publisher names, at least two distinct HTTPS hostnames, an impact score of 65+, and a recent event timestamp.
- Impact Ranking derives coverage from actual distinct publisher names, rejects implausibly future-dated events, and returns zero for empty events. Its weights remain editorial heuristics and require calibration against a labelled dataset.
- Source hostname-to-publisher identity, syndication detection, corporate ownership, and editorial independence are not yet verified. Two hostnames do not guarantee two independent newsrooms.
- No physical Android-device benchmark has yet validated model download time, peak RAM, latency, battery impact, or inference compatibility across ARM chipsets. Keep the neural model opt-in until device QA is complete.

## Model provenance and licensing

- Base model: `intfloat/multilingual-e5-small`, MIT license, 94-language multilingual text embedding model.
- Runtime: Microsoft ONNX Runtime Android; Hugging Face Tokenizers (DJL) for the published tokenizer JSON.
- Model and tokenizer downloads are pinned and SHA-256 checked. The app does not send article text to a hosted embedding API.
- Users can continue using PHOTON with the lexical fallback and do not need to download the neural model.
