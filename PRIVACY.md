# Privacy

Utter is an Android dictation app. It records speech while you use the bubble, turns it into text on the
device, and inserts the text into the focused field.

## What stays on your phone

- **Audio**: processed in memory by an on-device speech model and discarded. It is never uploaded and never
  written to storage.
- **Dictated text**: inserted into the field you chose. If history is on, a copy is stored in the app's private
  database on the phone (the last 25 to 500 entries, your choice). Turn history off, or clear it, in the app.
- **Settings**: stored in the app's private storage.

## What uses the network

Only downloading a speech model, when you tap its download button. The file comes from the
[sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) on GitHub. Nothing else
connects to the internet: there are no accounts, analytics, ads, crash reporting, or cloud transcription.

## Accessibility service

Utter uses an accessibility service only to show the bubble next to the keyboard and to insert the transcribed text
into the focused text field. It does not read, store, or transmit the contents of other apps.

## Debug mode

Off by default. When you switch it on it records technical events (service restarts, bubble visibility, microphone
health, timings) and a device and settings summary in a private file on the phone, so you can copy it for a bug
report. It never records audio or dictated text, switches itself off after 24 hours, and its log is deleted when it
is turned off.
