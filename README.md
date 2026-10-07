# Aoide

Offline voice typing for Android. A small floating bubble sits over your keyboard; speak, and your words are
typed into whatever text field is focused. Everything is processed **on your phone**: no account, no cloud, no
word limits.

It works with the keyboard you already use (including Gboard), because it types through an accessibility
service instead of replacing your keyboard.

## Features

- **Bubble** that appears only while a keyboard is up. Trigger by tap, hold-to-talk, or both. Adjustable
  idle opacity; remembers where you drag it, and follows screen rotation.
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

Aoide is sideloaded; it is not on Google Play (Play restricts accessibility apps).

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

On the phone:

1. Open Aoide and grant the microphone permission.
2. Turn on the Aoide accessibility service (Settings > Accessibility > Installed apps).
   On Android 13+ a sideloaded app may first need **App info > ⋮ > Allow restricted settings**.
3. Open the **Models** tab and download a model. Whisper Base (English) or Parakeet 110M are good starting points.
4. Set Aoide to **Unrestricted** under battery settings so Android does not stop the service.
   Samsung phones also have *Battery > Background usage limits > Never sleeping apps*.

Only `arm64-v8a` devices are supported (nearly all modern phones). Android 11 or newer.

## Privacy

Speech is processed on the device and is never uploaded. The only network use is downloading a model when you tap
download. See [PRIVACY.md](PRIVACY.md).

## Credits and licence

Aoide is a fork of [Phone Whisper](https://github.com/kafkasl/phone-whisper) by Pol Alvarez, licensed under the
Apache License 2.0, as is this project. See [LICENSE](LICENSE) and [NOTICE](NOTICE). The full third-party
licence texts are bundled in the app (Settings > About > Open-source licences) and live in
`app/src/main/assets/licenses/`.
