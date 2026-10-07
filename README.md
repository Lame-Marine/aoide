# Aoide

Offline voice typing for Android. A small floating bubble sits over your keyboard; speak, and your words are
typed into whatever text field is focused. Everything is processed **on your phone**: no account, no cloud, no
word limits.

It works with the keyboard you already use (including Gboard), because it types through an accessibility
service instead of replacing your keyboard.

## Features

- **Bubble** that appears only while a keyboard is up. Trigger by tap, hold-to-talk, or both. Adjustable
  size and idle opacity; remembers where you drag it, and follows screen rotation.
- **Fully local speech recognition** via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Pick a model in the
  app: NVIDIA Parakeet, OpenAI Whisper (tiny to turbo), Moonshine, FastConformer. Installed models are listed first,
  the active one on top; models can be uninstalled.
- **Language**: auto-detect or fixed (for multilingual Whisper models).
- **Silence trimming** (Silero VAD): skips silence and ignores clips with no speech.
- **Text cleanup**, all rule-based and offline: filler-word removal (editable list), capitalisation, optional
  spoken punctuation ("comma", "new line"...).
- **History** of past dictations, kept only on the phone; keep the last 25 to 500, or turn it off.
- **Debug mode** (off by default, switches itself off after 24 hours): records service and microphone events and a
  device report you can copy. Never records audio or dictated text.

## Install

1. On your phone, open the [latest release](https://github.com/Lame-Marine/aoide/releases/latest) and download
   `Aoide-<version>.apk`.
2. Open the downloaded file. If Android asks, allow your browser or files app to install apps from this source.
3. Open Aoide and grant the microphone permission. Read and accept the accessibility disclosure.
4. Turn on the Aoide accessibility service (Settings > Accessibility > Installed apps).
   On Android 13+ you may first need **App info > ⋮ > Allow restricted settings** for Aoide.
5. Open the **Models** tab and download a model. Whisper Base (English) or Parakeet 110M are good starting points.
6. Set Aoide to **Unrestricted** under battery settings so Android does not stop the service.
   Samsung phones also have *Battery > Background usage limits > Never sleeping apps*.

To update, install a newer APK over the old one; your models and settings are kept. Every release is signed with
the same key, and its SHA-256 fingerprint is listed in the release notes if you want to check it
(`apksigner verify --print-certs Aoide-<version>.apk`).

Requires an `arm64-v8a` phone (nearly all modern ones) running Android 11 or newer.

## Building from source

Requirements: JDK 17, the Android SDK (platform 34+), and for the one-time native build the Android NDK r27 and
CMake with Ninja. Android Studio's bundled JDK works.

```bash
# one-time: build the speech-engine native libraries WITHOUT text-to-speech.
# (The prebuilt upstream libraries link espeak-ng, which is GPL-3.0; building without it keeps the app free of
#  GPL code. The script downloads the sherpa-onnx source and ONNX Runtime, builds, and checks the result.)
ANDROID_NDK=/path/to/ndk/27.0.12077973 WORK=/path/without/spaces tools/build-native-libs.sh

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Privacy

Speech is processed on the device and is never uploaded. The only network use is downloading a model when you tap
download. See [PRIVACY.md](PRIVACY.md).

## Credits and licence

Aoide is a fork of [Phone Whisper](https://github.com/kafkasl/phone-whisper) by Pol Alvarez, licensed under the
Apache License 2.0, as is this project. See [LICENSE](LICENSE) and [NOTICE](NOTICE). The full third-party
licence texts are bundled in the app (Settings > About > Open-source licences) and live in
`app/src/main/assets/licenses/`.
