#!/usr/bin/env bash
# Builds the sherpa-onnx native libraries for Android arm64-v8a WITHOUT text-to-speech.
#
# Why: the prebuilt upstream libraries include the TTS stack, which statically links espeak-ng (GPL-3.0).
# Aoide only does speech-to-text, so we build with SHERPA_ONNX_ENABLE_TTS=OFF. That leaves only
# Apache-2.0 / MIT code (sherpa-onnx, kaldi-native-fbank, OpenFst, ONNX Runtime, ...) in the binaries.
#
# Needs: git, curl, unzip, CMake >= 3.22, Ninja, and Android NDK r27 (set ANDROID_NDK).
# Use a work directory WITHOUT spaces in its path.
#
#   ANDROID_NDK=/path/to/ndk/27.0.12077973 WORK=/c/sb tools/build-native-libs.sh
#
# Result: app/src/main/jniLibs/arm64-v8a/{libsherpa-onnx-jni.so,libonnxruntime.so}
set -euo pipefail

SHERPA_TAG="${SHERPA_TAG:-v1.12.28}"   # must match app/src/main/kotlin/com/k2fsa/sherpa/onnx (v1.12.28 - v1.12.33)
ORT_VERSION="${ORT_VERSION:-1.17.1}"
: "${ANDROID_NDK:?Set ANDROID_NDK to an Android NDK r27 directory}"
: "${WORK:?Set WORK to a build directory whose path has no spaces}"
CMAKE="${CMAKE:-cmake}"
NINJA="${NINJA:-}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
SRC="$WORK/sherpa-onnx"
BUILD="$WORK/build-android-arm64-v8a"
ORT="$WORK/onnxruntime-$ORT_VERSION"

mkdir -p "$WORK"

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$SHERPA_TAG" https://github.com/k2-fsa/sherpa-onnx.git "$SRC"
fi

# ONNX Runtime (MIT) prebuilt for Android, as used by sherpa-onnx's own build script
if [ ! -f "$ORT/jni/arm64-v8a/libonnxruntime.so" ]; then
  mkdir -p "$ORT"
  zip="$WORK/onnxruntime-android-$ORT_VERSION.zip"
  curl -fL "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$ORT_VERSION/onnxruntime-android-$ORT_VERSION.zip" -o "$zip"
  unzip -q -o "$zip" -d "$ORT"
  rm -f "$zip"
fi

export SHERPA_ONNXRUNTIME_LIB_DIR="$ORT/jni/arm64-v8a/"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ORT/headers/"

GEN=()
if [ -n "$NINJA" ]; then GEN=(-G Ninja "-DCMAKE_MAKE_PROGRAM=$NINJA"); fi

"$CMAKE" "${GEN[@]}" -S "$SRC" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DSHERPA_ONNX_ENABLE_TTS=OFF \
  -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF \
  -DSHERPA_ONNX_ENABLE_BINARY=OFF \
  -DSHERPA_ONNX_ENABLE_C_API=OFF \
  -DSHERPA_ONNX_ENABLE_JNI=ON \
  -DSHERPA_ONNX_ENABLE_PYTHON=OFF -DSHERPA_ONNX_ENABLE_TESTS=OFF \
  -DSHERPA_ONNX_ENABLE_CHECK=OFF -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
  -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
  -DBUILD_PIPER_PHONMIZE_EXE=OFF -DBUILD_PIPER_PHONMIZE_TESTS=OFF \
  -DBUILD_ESPEAK_NG_EXE=OFF -DBUILD_ESPEAK_NG_TESTS=OFF \
  -DCMAKE_INSTALL_PREFIX="$BUILD/install"

"$CMAKE" --build "$BUILD" --parallel
"$CMAKE" --install "$BUILD" --strip

mkdir -p "$DEST"
cp -fv "$BUILD/install/lib/libsherpa-onnx-jni.so" "$DEST/"
cp -fv "$ORT/jni/arm64-v8a/libonnxruntime.so" "$DEST/"
# the C/C++ API libraries are not used by the Kotlin/JNI path
rm -f "$DEST/libsherpa-onnx-c-api.so" "$DEST/libsherpa-onnx-cxx-api.so"

echo "--- sanity check: no espeak / TTS code in the result"
# note: plain "espeak" also matches the unrelated word "wespeaker", so look for real espeak-ng markers
if grep -a -q -E "espeak-ng|ESPEAK_DATA_PATH|phondata|piper-phonemize|Java_com_k2fsa_sherpa_onnx_OfflineTts" "$DEST/libsherpa-onnx-jni.so"; then
  echo "FAIL: espeak-ng / TTS code still present" >&2
  exit 1
fi
echo "OK: libsherpa-onnx-jni.so contains no espeak-ng / TTS code"
ls -la "$DEST"
