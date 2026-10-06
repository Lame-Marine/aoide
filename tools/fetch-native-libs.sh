#!/usr/bin/env bash
# Downloads the prebuilt sherpa-onnx native libraries (arm64-v8a) and places them where Gradle packages them.
# They are taken from the upstream Phone Whisper release APK and are gitignored, so run this once after cloning.
set -euo pipefail
cd "$(dirname "$0")/.."

URL="https://github.com/kafkasl/phone-whisper/releases/download/v0.3.0/phone-whisper-v0.3.0-debug.apk"
DEST="app/src/main/jniLibs/arm64-v8a"

mkdir -p "$DEST"
# keep the download inside the project (build/ is gitignored): avoids temp-dir path differences between tools
mkdir -p build
tmp="build/native-libs-download.apk"
trap 'rm -f "$tmp"' EXIT

echo "Downloading $URL"
curl -fL "$URL" -o "$tmp"
unzip -o -j "$tmp" "lib/arm64-v8a/*.so" -d "$DEST"
echo "Installed:"
ls -la "$DEST"
