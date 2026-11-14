#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:-}"
MAX_BYTES=$((85 * 1024 * 1024))
REQUIRED_LIBS=(
  libdjl_tokenizer.so
  libonnxruntime.so
  libonnxruntime4j_jni.so
  libtranslate_jni.so
  libandroidx.graphics.path.so
)

if [[ -z "$APK_PATH" || ! -f "$APK_PATH" ]]; then
  echo "Usage: $0 <apk-path>" >&2
  exit 2
fi
if ! command -v unzip >/dev/null 2>&1; then
  echo "ERROR: unzip is required" >&2
  exit 2
fi

entries="$(unzip -Z1 "$APK_PATH")"
failed=0

abis="$(printf '%s\n' "$entries" | awk -F/ '$1 == "lib" && NF >= 3 { print $2 }' | sort -u)"
if [[ "$abis" != "arm64-v8a" ]]; then
  echo "ERROR: expected only arm64-v8a native libraries; found: ${abis:-<none>}"
  failed=1
else
  echo "PASS: native ABI is arm64-v8a only"
fi

for library in "${REQUIRED_LIBS[@]}"; do
  if grep -Fqx "lib/arm64-v8a/$library" <<<"$entries"; then
    echo "PASS: required JNI present: $library"
  else
    echo "ERROR: required JNI missing: $library"
    failed=1
  fi
done

desktop_patterns=(
  'com/sun/jna/aix-ppc/'
  'com/sun/jna/aix-ppc64/'
  'com/sun/jna/darwin-aarch64/'
  'com/sun/jna/darwin-x86-64/'
  'com/sun/jna/win32-aarch64/'
  'com/sun/jna/win32-x86-64/'
  'com/sun/jna/win32-x86/'
  'native/lib/osx-'
  'native/lib/win-'
)
for pattern in "${desktop_patterns[@]}"; do
  matches="$(printf '%s\n' "$entries" | grep -F "$pattern" || true)"
  if [[ -n "$matches" ]]; then
    echo "ERROR: desktop-only resources remain for '$pattern':"
    printf '%s\n' "$matches"
    failed=1
  fi
done
if [[ "$failed" -eq 0 ]]; then
  echo "PASS: no targeted desktop-only resources"
fi

size_bytes="$(wc -c < "$APK_PATH" | tr -d '[:space:]')"
if (( size_bytes <= MAX_BYTES )); then
  echo "PASS: APK size ${size_bytes} bytes is within 85 MiB"
else
  echo "ERROR: APK size ${size_bytes} bytes exceeds 85 MiB (${MAX_BYTES} bytes)"
  failed=1
fi

if (( failed != 0 )); then
  echo "APK verification FAILED"
  exit 1
fi
echo "APK verification PASSED"
