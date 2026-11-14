# PHOTON AI NEWS — Personal Edition, Design Specification v3.2

**Status:** Approved by the user on 2026-11-14  
**Scope:** Private/personal Android build of PHOTON AI NEWS  
**Implementation status at approval:** Design only; no code changes authorized until the implementation plan is separately approved.

## 1. Purpose

Produce one compact Android APK optimized for the user's confirmed Android device. Preserve the existing PHOTON AI NEWS product behavior and the optional local multilingual E5 model while removing unnecessary desktop and non-target Android payloads.

This is a personal build, not a public product launch. No public release, paid AI API or hosted LLM is in scope.

## 2. Goals

- Deliver one installable APK for the confirmed device ABI, expected to be `arm64-v8a` but requiring device confirmation.
- Reduce the APK to **≤85 MiB**.
- Preserve all existing application features and user data.
- Keep multilingual E5 optional, explicitly user-installed, locally stored and SHA-256 verified.
- Preserve Android ONNX Runtime and DJL Tokenizer JNI dependencies.
- Improve model startup, inference robustness, memory use and latency only when measurements justify a change.
- Sign the personal APK with a private keystore stored outside the repository and protected GitHub Actions secrets.

## 3. Non-goals

- No Windows, macOS, Linux desktop or cross-platform distribution.
- No public Google Play release or public GitHub Release.
- No hosted AI inference, paid AI API or upload of user/news text to an external AI service.
- No replacement of the existing local E5 model solely because its current filename includes `avx512_vnni`.
- No removal of Android dependencies or native libraries without artifact inspection and regression evidence.
- No redesign or removal of existing user-facing features.

## 4. Packaging and ABI

- Confirm the target device's supported ABIs before applying an arm64-only filter.
- Once confirmed, package only `arm64-v8a` in the single APK.
- Exclude only verified Windows, macOS, AIX and other non-Android JNA/tokenizer resources.
- Preserve Android JNI libraries required by:
  - DJL Tokenizers;
  - ONNX Runtime;
  - translation;
  - AndroidX graphics.
- Validate the actual APK contents after every packaging change.
- Do not use ABI splits; deliver one APK.
- Target APK size: **≤85 MiB**.

## 5. Local E5 model

- Preserve `intfloat/multilingual-e5-small`, current pinned model revision, tokenizer, URLs and SHA-256 values until compatibility tests prove a change is necessary.
- Keep the model outside the APK; download it only after explicit user action.
- Keep model files in app-private storage.
- Verify both model and tokenizer SHA-256 before reuse and after download.
- Reject partial, corrupt or oversized downloads; use temporary files and promote only verified files.
- Keep lexical fallback available if the model is declined, missing, corrupt, incompatible or fails to initialize.
- Do not transmit news text to a hosted inference service.

## 6. ONNX Runtime and tokenizer

- Preserve ONNX Runtime Android and DJL Android Tokenizer dependencies.
- Validate model inputs `input_ids`, `attention_mask` and `token_type_ids`.
- Validate output token count and exact embedding dimension **384**.
- Use attention-mask-aware mean pooling and L2 normalization.
- Reject non-finite output values, invalid tensor shapes and zero-norm vectors.
- Keep cosine similarity bounded to **[-1, 1]**.
- Ensure ONNX tensors, results, tokenizer and session resources are closed correctly.
- Measure initialization time, inference latency, process memory, CPU and thermal/battery behavior on the actual target device.
- Do not infer ARM64 compatibility from the model filename or CI success.

## 7. Preserve application behavior and data

The optimization must preserve:

- RSS/Atom feed refresh and important-news selection;
- categories, filters, search and sorting;
- event clustering and event details;
- Breaking AI and Global Breaking;
- Source Evidence and source provenance transparency;
- Fact Cross-Check heuristic and its non-fact-checker disclaimer;
- AI Impact Ranking;
- on-device ML Kit translation;
- SQLite news archive and bookmarks;
- WorkManager background synchronization and notifications;
- Editorial Composer and source traceability;
- optional local E5 semantic model and lexical fallback.

Do not change the database schema or delete existing app data as part of APK optimization.

## 8. Signing and secret management

- Use a private keystore controlled by the user.
- Store the keystore only in the user's secure custody and as a protected GitHub Environment secret in encoded form.
- Never commit the keystore, decoded key, signing properties or secret values.
- Configure signing through environment variables supplied by GitHub Actions.
- Use a protected GitHub Environment named `personal-release`.
- Use the secret names:
  - `PHOTON_KEYSTORE_BASE64`
  - `PHOTON_KEYSTORE_PASSWORD`
  - `PHOTON_KEY_ALIAS`
  - `PHOTON_KEY_PASSWORD`
- Run signed builds only from an explicitly created `personal-v*` tag. Do not expose signing secrets to pull-request jobs.
- Verify the final APK signature with Android `apksigner`.
- Preserve the same signing certificate for future updates; document secure backup and certificate fingerprint handling.

## 9. Acceptance criteria

| Area | Acceptance criterion |
|---|---|
| Device compatibility | Target device reports `arm64-v8a`; otherwise stop and revisit ABI choice |
| APK format | One APK, no ABI split set |
| APK size | ≤85 MiB |
| ABI payload | Only `arm64-v8a` |
| Desktop payload | No unnecessary Windows/macOS/AIX JNA or desktop tokenizer resources |
| Android JNI | Required ARM64 native libraries are present |
| Unit tests | All existing and new unit tests pass |
| Model integrity | Model and tokenizer SHA-256 values match pinned hashes |
| Embeddings | Exactly 384 finite values; masked pooling; L2-normalized |
| Cosine | Always bounded to [-1, 1] |
| Fallback | App remains usable when model install or inference fails |
| Data | Existing archive, bookmarks and preferences survive update |
| Responsiveness | AI analysis does not block the main thread |
| Installation | Signed APK installs and updates on the target Android device |
| Performance | Short-text E5 inference p50 ≤500 ms and p95 ≤1,000 ms on the target device; measure rather than assume |
| Signing | Signature verifies and remains stable across updates; key material absent from Git |

## 10. Evidence and reporting

Report three evidence classes separately:

1. **Automated CI:** unit tests, build, artifact upload and instrumentation-test compilation.
2. **Static APK inspection:** size, ABI directories, required JNI libraries, desktop resource exclusions and signature verification.
3. **Physical-device QA:** actual installation/update, feature regression, model inference, p50/p95, memory and thermal observations.

CI success does not count as physical-device QA. Any metric not actually measured must be reported as not measured.
