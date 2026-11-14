# PHOTON AI NEWS Personal Edition v3.2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce one compact, installable, privately signed Android APK for the confirmed target device, preserving PHOTON AI NEWS features and its optional local multilingual E5 model.

**Architecture:** Keep the existing Kotlin/Compose application and its local-first editorial pipeline. Reduce the APK at packaging time by targeting only the confirmed device ABI and excluding only verified desktop-only native resources; harden model asset integrity and ONNX output validation without changing the model or removing Android JNI dependencies. Keep debug CI separate from a protected, tag-triggered signed personal build.

**Tech Stack:** Android Gradle Plugin 8.7.3; Gradle 8.9; Kotlin 2.0.21; Java 17; Android SDK compile/target 35, minSdk 26; Jetpack Compose; ONNX Runtime Android 1.31.1; DJL Hugging Face Tokenizers 0.33.0; JUnit 4.13.2; GitHub Actions.

**Spec:** Approved in the ChatGPT conversation on 2026-11-14: “PHOTON AI NEWS — Personal Edition, Design Specification v3.2”. The accepted requirements and thresholds are restated below so this plan can be executed independently. The design document has not yet been committed as a separate repository file.

## Global Constraints

- Personal Android application only; do not turn this work into a public product or publish a public release.
- Preserve the existing RSS/Atom feed, categories and filters, event clustering, Breaking AI and Global Breaking, Source Evidence, Source Trust, Fact Cross-Check, AI Impact Ranking, ML Kit translation, SQLite archive, bookmarks, WorkManager synchronization, notifications, Editorial Composer, local E5 model and lexical fallback.
- Preserve existing application data, database schema, bookmarks, preferences and local model storage.
- Keep the multilingual E5 model optional and user-initiated; do not bundle the model into the APK or upload news text to a hosted AI service.
- Target only `arm64-v8a` only after the target device reports that ABI in `ro.product.cpu.abilist`; stop and revisit the design if it does not.
- Produce one APK, not an ABI split set; target final APK size is **≤85 MiB**.
- Preserve Android JNI libraries required by ONNX Runtime, DJL Tokenizers, translation and AndroidX graphics.
- Exclude only verified Windows, macOS and other non-Android resources; do not remove a dependency based only on its name.
- E5 output must be exactly **384 finite dimensions**, pooled with the attention mask and L2-normalized; cosine similarity must remain in **[-1, 1]**.
- If model files are missing, corrupt, incomplete, incompatible or fail to initialize, the app must remain usable through lexical fallback.
- Keep signing material out of Git. Use GitHub Environment `personal-release` secrets named `PHOTON_KEYSTORE_BASE64`, `PHOTON_KEYSTORE_PASSWORD`, `PHOTON_KEY_ALIAS` and `PHOTON_KEY_PASSWORD`.
- Never expose signing secrets in command-line arguments, logs, artifacts or repository files. Never commit a keystore, key properties file or decoded signing key.
- Run signed builds only from an explicitly created `personal-v*` tag; never expose signing secrets to pull-request workflows.
- Create implementation work on a new branch based on the verified PR #3 head. Do not modify PR #1, #2 or #3; do not change `main`; do not merge any PR.
- Separate CI, static APK inspection and physical-device QA in all reports. Do not claim physical Android testing unless it was actually run on the target device.

## Review Focus

1. **Wrong or unsupported target ABI:** an arm64-only APK must not be produced until the target device confirms `arm64-v8a`; a mismatch must stop the release rather than produce an unusable APK.
2. **Corrupt, truncated or partially downloaded model/tokenizer:** installation must reject bad SHA-256 values, clean temporary files and preserve lexical fallback without crashing the app.
3. **Unexpected ONNX output:** wrong tensor rank, token count, embedding dimension, NaN/Infinity or zero-norm output must be rejected and fall back safely.
4. **Long input, memory pressure or thermal throttling:** text up to the existing 4,000-character input cap and 512-token limit must not crash inference; benchmark results must be reported separately from CI.
5. **Missing or mis-scoped signing secrets:** debug and PR workflows must still run without secrets; signed builds must fail closed and must never run for pull requests or untrusted refs.

---

## Repository Baseline and File Map

Verified implementation base: `feature/semantic-model-v3.1` at `271707776acb2a4b40b160b71501f4043c56d6f1`. The branch was compared against that SHA and was identical when this plan was written.

Current relevant files:

- `app/build.gradle.kts` — Android SDK, ABI filters, packaging exclusions, signing configuration and test dependencies.
- `app/src/main/java/com/lunacode/photonnews/data/E5ModelAssets.kt` — E5 model/tokenizer download, local-file discovery and SHA-256 checks.
- `app/src/main/java/com/lunacode/photonnews/data/MultilingualE5Embedder.kt` — tokenizer, ONNX tensors/session, masked mean pooling, normalization, cache and model registry.
- `app/src/main/java/com/lunacode/photonnews/data/EditorialIntelligenceV3.kt` — semantic/fallback selection and editorial scoring; change only if tests prove a necessary integration defect.
- `app/src/main/java/com/lunacode/photonnews/MainActivity.kt` — explicit model-install UI, status reporting and app startup; preserve the existing user flow.
- `app/src/test/java/com/lunacode/photonnews/data/EditorialIntelligenceV3Test.kt` — current editorial regression tests.
- `.github/workflows/android.yml` — current PR/push debug CI.
- `.gitignore` — add protection against accidental local keystore commits.
- Create `scripts/verify-personal-apk.sh` — deterministic ABI/JNI/desktop-resource/size inspection.
- Create `app/src/test/java/com/lunacode/photonnews/data/E5ModelAssetsTest.kt` — pure file-integrity and download-state tests.
- Create `app/src/test/java/com/lunacode/photonnews/data/EmbeddingOutputValidatorTest.kt` — ONNX output-shape and normalization tests.
- Create `app/src/androidTest/java/com/lunacode/photonnews/data/E5DeviceSmokeTest.kt` — on-device inference smoke test and latency sampling.
- Create `.github/workflows/personal-release.yml` — tag-triggered signed personal APK workflow.
- Create `docs/release/personal-edition-signing.md` — private keystore creation, backup, secret configuration and signature verification instructions.

Do not rename or relocate existing application classes as part of this work. Keep changes narrowly scoped to the files above unless inspection during implementation proves another file is required.

## Implementation Tasks

### Task 1: Establish target-device and repository gates

**Files:** No source changes.

**Interfaces:**
- Consumes: current PR #3 head `271707776acb2a4b40b160b71501f4043c56d6f1`.
- Produces: a recorded target ABI result and a clean baseline against which the optimized build can be compared.

- [ ] **Step 1: Confirm the target ABI on the actual phone.**

  Run on a connected target device:

  ```bash
  adb shell getprop ro.product.cpu.abilist
  adb shell getprop ro.product.cpu.abi
  ```

  Expected: the ABI list contains `arm64-v8a`. If it does not, stop before applying an arm64-only filter.

- [ ] **Step 2: Capture the current baseline.**

  Run:

  ```bash
  gradle --no-daemon :app:testDebugUnitTest
  gradle --no-daemon :app:assembleDebug
  ```

  Record test results, APK byte size and the current ABI/native-resource inventory. The previously inspected debug APK was 265,351,998 bytes (~253.1 MiB); treat that as historical baseline, not as the current build result.

- [ ] **Step 3: Confirm protected repository state.**

  Verify PR #1, #2 and #3 remain open and their heads have not moved. Verify `main` has not changed. Create the implementation branch only after plan approval, from the then-current PR #3 head.

### Task 2: Restrict APK packaging to the confirmed Android ABI

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `scripts/verify-personal-apk.sh`

**Interfaces:**
- Consumes: target ABI confirmation from Task 1.
- Produces: an APK containing only `arm64-v8a` native libraries and a reusable artifact verifier.

- [ ] **Step 1: Write the failing APK-inventory verifier.**

  Add checks to `scripts/verify-personal-apk.sh` for:
  - exactly one ABI directory: `arm64-v8a`;
  - required JNI libraries: `libdjl_tokenizer.so`, `libonnxruntime.so`, `libonnxruntime4j_jni.so`, `libtranslate_jni.so` and `libandroidx.graphics.path.so`;
  - absence of verified desktop-only JNA AIX/macOS/Windows resources and `native/lib/osx-*` / `native/lib/win-*` resources;
  - final APK size no greater than `85 * 1024 * 1024` bytes.

  Run it against the baseline APK and confirm it fails for the expected multi-ABI/size reasons.

- [ ] **Step 2: Apply the ABI filter.**

  Add `arm64-v8a` to the Android NDK ABI filters in `app/build.gradle.kts`, keeping the filter in one authoritative place. Do not introduce ABI splits or a universal APK.

- [ ] **Step 3: Exclude only verified desktop resources.**

  Use Android Gradle resource packaging exclusions for the exact desktop-only paths found in the APK/JAR inventory. Do not exclude Android resource paths or any of the required JNI libraries. If an exclusion pattern is broader than the inspected path, narrow it before proceeding.

- [ ] **Step 4: Build and inspect the APK.**

  Run:

  ```bash
  gradle --no-daemon :app:clean :app:assembleDebug
  bash scripts/verify-personal-apk.sh app/build/outputs/apk/debug/app-debug.apk
  ```

  Expected: verifier passes; only `arm64-v8a` is packaged; all five required JNI libraries are present; no targeted desktop resources remain; APK is ≤85 MiB.

- [ ] **Step 5: Commit the packaging change.**

  ```bash
  git add app/build.gradle.kts scripts/verify-personal-apk.sh
  git commit -m "build(android): package personal edition for arm64"
  ```

### Task 3: Make E5 model installation integrity-safe

**Files:**
- Modify: `app/src/main/java/com/lunacode/photonnews/data/E5ModelAssets.kt`
- Create: `app/src/test/java/com/lunacode/photonnews/data/E5ModelAssetsTest.kt`

**Interfaces:**
- Consumes: existing model/tokenizer URLs, pinned revision, expected SHA-256 values and current private app storage location.
- Produces: `E5ModelAssets.existing(context): E5ModelFiles?` that returns files only when both files pass integrity checks; `E5ModelAssets.install(context): E5ModelFiles` that repairs invalid files through verified temporary downloads.

- [ ] **Step 1: Add pure integrity tests.**

  Test valid SHA-256, mismatched SHA-256, empty file and truncated/corrupt file cases. Use temporary files and JUnit; do not require an Android device for these tests.

- [ ] **Step 2: Run the new tests and confirm the failure.**

  Run:

  ```bash
  gradle --no-daemon :app:testDebugUnitTest --tests '*E5ModelAssetsTest'
  ```

  Expected before implementation: failing tests for the missing integrity contract.

- [ ] **Step 3: Extract and implement file-integrity verification.**

  Add this pure helper in `E5ModelAssets.kt` and keep its API stable for unit tests:

  ```kotlin
  internal object ModelFileIntegrity {
      fun sha256(file: File): String
      fun matches(file: File, expectedSha256: String): Boolean
  }
  ```

  Make `E5ModelAssets.existing(context): E5ModelFiles?` verify both pinned hashes, not merely file existence and nonzero length. Test names: `acceptsExpectedSha256`, `rejectsModifiedFile`, `rejectsEmptyFile` and `rejectsTruncatedFile`.

- [ ] **Step 4: Preserve safe installation semantics.**

  Keep downloads on `Dispatchers.IO`; enforce existing maximum sizes; write to `.part`; verify the completed SHA-256 before atomic rename; remove partial files on every failure. A corrupt pre-existing file must be replaced on the next explicit install attempt. Do not silently change the pinned revision, URL or model filename.

- [ ] **Step 5: Verify recovery and commit.**

  Run the focused test and full `:app:testDebugUnitTest`. Confirm invalid files are not returned by `existing()`, valid files are reused, and the lexical fallback remains available after an installation failure.

  ```bash
  git add app/src/main/java/com/lunacode/photonnews/data/E5ModelAssets.kt app/src/test/java/com/lunacode/photonnews/data/E5ModelAssetsTest.kt
  git commit -m "fix(ai): verify local E5 model assets before reuse"
  ```

### Task 4: Enforce the ONNX embedding contract

**Files:**
- Modify: `app/src/main/java/com/lunacode/photonnews/data/MultilingualE5Embedder.kt`
- Create: `app/src/test/java/com/lunacode/photonnews/data/EmbeddingOutputValidatorTest.kt`
- Modify only if required: `app/src/test/java/com/lunacode/photonnews/data/EditorialIntelligenceV3Test.kt`

**Interfaces:**
- Consumes: ONNX output tensor, token IDs and attention mask.
- Produces: a validated `FloatArray` of exactly 384 finite values, masked-mean-pooled and L2-normalized.

- [ ] **Step 1: Add deterministic output-contract tests.**

  Cover:
  - valid token output produces 384 values;
  - padding tokens do not contribute to mean pooling;
  - every output value is finite;
  - output norm is approximately 1;
  - wrong embedding dimension, wrong token count, mismatched attention mask, empty output, all-masked output, NaN/Infinity and zero-norm output are rejected;
  - cosine similarity remains bounded in `[-1, 1]` and mismatched dimensions do not crash the caller.

- [ ] **Step 2: Run the focused tests and confirm they fail.**

  ```bash
  gradle --no-daemon :app:testDebugUnitTest --tests '*EmbeddingOutputValidatorTest'
  ```

- [ ] **Step 3: Implement a pure output validator/pooling helper.**

  Define this pure helper in `MultilingualE5Embedder.kt` and keep its API stable for unit tests:

  ```kotlin
  internal object EmbeddingOutputValidator {
      fun poolAndNormalize(
          hidden: Array<Array<FloatArray>>,
          attentionMask: LongArray
      ): FloatArray
  }
  ```

  Enforce batch size 1, token-count/mask alignment, exactly 384 dimensions per token, finite values and a nonzero finite norm. Keep the existing E5 `passage:` prefix, 512-token cap, attention-mask pooling and L2 normalization. Test names: `poolsAndNormalizes384DimensionalEmbeddings`, `ignoresMaskedPaddingTokens`, `rejectsWrongEmbeddingDimension`, `rejectsWrongTokenCount`, `rejectsMismatchedMask`, `rejectsNonFiniteValues` and `rejectsZeroNorm`.

- [ ] **Step 4: Integrate validation into inference.**

  Replace unchecked output casting/pooling in `MultilingualE5Embedder.embed()` with the tested helper. Keep ONNX tensors and result objects closed on all paths. Keep inference failures contained by `SemanticModelRegistry.similarity()`, so callers receive lexical fallback rather than an app crash.

- [ ] **Step 5: Verify and commit.**

  Run the focused tests and the complete unit-test suite. Do not change the pinned ONNX model based only on the `avx512_vnni` filename; actual ARM64 inference compatibility is a device gate in Task 6.

  ```bash
  git add app/src/main/java/com/lunacode/photonnews/data/MultilingualE5Embedder.kt app/src/test/java/com/lunacode/photonnews/data/EmbeddingOutputValidatorTest.kt
  git commit -m "fix(ai): validate multilingual E5 embedding output"
  ```

### Task 5: Add a physical-device E5 smoke test and benchmark

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/java/com/lunacode/photonnews/data/E5DeviceSmokeTest.kt`

**Interfaces:**
- Consumes: verified model assets and the validated `SemanticModelRegistry` from Tasks 3–4.
- Produces: on-device proof that the Android tokenizer and ONNX Runtime load and infer on the target ARM64 device, plus p50/p95 latency measurements.

- [ ] **Step 1: Add Android test dependencies and runner configuration.**

  Add compatible AndroidX Test runner/JUnit dependencies and set the instrumentation runner. Keep these dependencies test-only.

- [ ] **Step 2: Implement an on-device smoke test.**

  The test must require an already installed, SHA-verified E5 model; it must not silently download a 118 MB model during a normal CI run. Add test methods `e5ProducesFinite384DimensionalRussianAndEnglishEmbeddings` and `reportsShortTextLatencyPercentiles`. On the device, instantiate `MultilingualE5Embedder(files)` inside a `use` block, call `embed()` for Russian and English inputs, verify finite 384-dimensional embeddings and bounded cosine similarity, then close the embedder automatically. Also verify the registry integration path returns a non-null similarity after `SemanticModelRegistry.install(files)` and always call `SemanticModelRegistry.shutdown()` in `finally`.

- [ ] **Step 3: Implement repeatable latency sampling.**

  Run 5 warm-up inferences followed by at least 30 measured short-text inferences. Report median (p50), p95 and peak process memory/PSS where available. Keep benchmark output local to the test/logcat; do not transmit it.

- [ ] **Step 4: Compile the instrumentation test in CI.**

  Run `gradle --no-daemon :app:assembleDebugAndroidTest`. This proves test compilation only; it is not a physical-device test.

- [ ] **Step 5: Run on the actual target device after model installation.**

  Use a connected device and `gradle --no-daemon :app:connectedDebugAndroidTest` or the equivalent `adb` instrumentation command. Record device model, Android version, ABI, model hash, p50, p95 and memory. Acceptance target: p50 ≤500 ms and p95 ≤1,000 ms for the defined short-text sample. If the target misses these values, profile before changing thread counts or model artifacts.

- [ ] **Step 6: Commit the smoke test.**

  ```bash
  git add app/build.gradle.kts app/src/androidTest/java/com/lunacode/photonnews/data/E5DeviceSmokeTest.kt
  git commit -m "test(android): add on-device E5 inference smoke test"
  ```

### Task 6: Integrate deterministic APK verification into CI

**Files:**
- Modify: `.github/workflows/android.yml`
- Modify: `scripts/verify-personal-apk.sh`

**Interfaces:**
- Consumes: arm64 APK and verifier from Task 2, JVM tests from Tasks 3–4, instrumentation APK from Task 5.
- Produces: CI evidence for unit tests, APK packaging and instrumentation-test compilation.

- [ ] **Step 1: Extend the existing Android CI job.**

  Keep the current theme-resource check, editorial unit tests, debug build and artifact upload. Add:
  - complete `:app:testDebugUnitTest`;
  - `:app:assembleDebugAndroidTest`;
  - APK verification against the built debug APK;
  - artifact upload only after all required checks pass.

- [ ] **Step 2: Ensure CI does not require signing secrets.**

  The PR/debug workflow must build and test with no keystore and no signing secrets. Do not add secrets to the `pull_request` event.

- [ ] **Step 3: Verify the workflow on the implementation branch.**

  Expected: all JVM tests pass, debug APK and Android test APK compile, APK verifier passes and artifact upload succeeds. The CI run must be tied to the exact implementation commit SHA.

- [ ] **Step 4: Commit the CI change.**

  ```bash
  git add .github/workflows/android.yml scripts/verify-personal-apk.sh
  git commit -m "ci(android): verify personal APK packaging"
  ```

### Task 7: Add protected private-keystore signing

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.gitignore`
- Create: `.github/workflows/personal-release.yml`
- Create: `docs/release/personal-edition-signing.md`

**Interfaces:**
- Consumes: the release build variant and verified APK packaging from earlier tasks; four secrets from GitHub Environment `personal-release`.
- Produces: a signed personal APK artifact whose signing certificate remains stable across updates.

- [ ] **Step 1: Add secret-safe Gradle signing configuration.**

  Read these exact environment variables in Gradle: `PHOTON_KEYSTORE_PATH`, `PHOTON_KEYSTORE_PASSWORD`, `PHOTON_KEY_ALIAS` and `PHOTON_KEY_PASSWORD`. The workflow maps the decoded temporary file path to `PHOTON_KEYSTORE_PATH`; the other three values come from same-named GitHub Environment secrets. Keep debug builds independent of these values. Do not write credentials to source files, `gradle.properties`, build outputs or logs.

- [ ] **Step 2: Add local secret-file protections.**

  Extend `.gitignore` for keystore formats and local signing-property files. Add a repository scan/check that fails if a keystore or signing-properties file is staged.

- [ ] **Step 3: Add a tag-only signed-build workflow.**

  Trigger the signing workflow only for tags matching `personal-v*`. Bind the job to GitHub Environment `personal-release`; use read-only repository permissions. Do not add `pull_request`, `pull_request_target` or broad branch-push triggers to this workflow.

- [ ] **Step 4: Materialize and destroy the keystore safely.**

  Decode `PHOTON_KEYSTORE_BASE64` into a file under `RUNNER_TEMP` with restrictive permissions; pass its path and the remaining secrets as environment variables; delete the file in an unconditional cleanup step. Fail before Gradle if any required secret is absent. Never echo secret values or print the decoded keystore.

- [ ] **Step 5: Verify the resulting signature.**

  Run `apksigner verify --verbose --print-certs` against the release APK. Record only the public certificate fingerprint and signature-verification result. Confirm that the artifact is signed and passes the APK inventory verifier.

- [ ] **Step 6: Document keystore custody and GitHub setup.**

  Document local key generation, secure offline backup, the exact four secret names, Environment setup, tag naming, signature verification and the critical warning that losing the keystore prevents in-place updates signed with the same application identity. Do not create or upload a keystore on the user's behalf without a separate explicit instruction.

- [ ] **Step 7: Commit signing support.**

  ```bash
  git add app/build.gradle.kts .gitignore .github/workflows/personal-release.yml docs/release/personal-edition-signing.md
  git commit -m "build(release): add protected personal APK signing"
  ```

### Task 8: Full regression, update-install and release acceptance

**Files:**
- Modify only if a verified defect is found: relevant existing app/test files.
- Update: `docs/release/personal-edition-signing.md` with final reproducible release procedure.

**Interfaces:**
- Consumes: verified debug APK, signed release APK, test evidence and the user's target Android device.
- Produces: an acceptance report that clearly separates CI, static inspection and physical-device results.

- [ ] **Step 1: Run the complete automated suite.**

  ```bash
  gradle --no-daemon clean :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
  bash scripts/verify-personal-apk.sh app/build/outputs/apk/debug/app-debug.apk
  ```

- [ ] **Step 2: Run functional regression on the device.**

  Verify feed refresh, categories, search, event grouping, Breaking, Sources, Impact, bookmarks, archive persistence, translation, notifications/WorkManager behavior, Editorial Composer, explicit E5 installation, model restart/load and lexical fallback after model failure.

- [ ] **Step 3: Verify update compatibility.**

  Install the prior app, create bookmarks and retain sample archive data, then install the signed v3.2 APK with `adb install -r`. Confirm the update succeeds with the same application ID and signing certificate and that saved data remains available.

- [ ] **Step 4: Verify offline and failure behavior.**

  With network disabled, confirm previously downloaded model inference and local archive behavior remain available. Simulate missing/corrupt model files in a controlled test setup and confirm the app uses lexical fallback without crashing.

- [ ] **Step 5: Record physical-device performance.**

  Record actual E5 p50/p95, model initialization time, process memory/PSS and observed thermal/battery behavior. Mark any unavailable metric as not measured; do not infer it from CI.

- [ ] **Step 6: Prepare the stacked draft PR.**

  After implementation and review, open a new draft PR from `feature/personal-edition-v3.2` into `feature/semantic-model-v3.1`, not into `main`. Keep PR #1, #2 and #3 open. Do not merge the new PR or any existing PR.

- [ ] **Step 7: Create a signed personal build only after release approval.**

  After the user confirms the final artifact and the GitHub Environment secrets are configured, create a `personal-v3.2.*` tag on the approved implementation commit. Verify the signed APK artifact and provide its certificate fingerprint and SHA-256. Do not publish a GitHub Release.

## Acceptance Checklist

- [ ] Target device reports `arm64-v8a`; otherwise arm64-only build is blocked.
- [ ] Exactly one APK is produced; ABI inventory contains only `arm64-v8a`.
- [ ] Required Android JNI libraries are present.
- [ ] Verified desktop-only Windows/macOS/AIX resources are absent.
- [ ] APK size ≤85 MiB.
- [ ] All existing and new JVM tests pass.
- [ ] Android instrumentation test compiles.
- [ ] On-device E5 tokenizer and ONNX inference pass on the target phone.
- [ ] Embeddings contain exactly 384 finite values and are L2-normalized.
- [ ] Corrupt/partial model files are rejected; lexical fallback remains operational.
- [ ] E5 short-text p50 ≤500 ms and p95 ≤1,000 ms on the target phone, or the deviation and optimization plan are reported.
- [ ] All existing app functionality and local user data are preserved.
- [ ] Signed APK verifies with the private certificate; repeat update installation succeeds.
- [ ] Keystore exists only in the user's secure custody and GitHub Environment secrets; no key material is present in Git history or artifacts.
- [ ] PR #1, #2 and #3 remain open; `main` remains unchanged; no PR is merged.
- [ ] CI, static APK inspection and physical-device QA are reported as separate evidence classes.
