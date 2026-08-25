# Essential Voice — downloads

Hold-to-talk dictation for the CMF Phone 2 Pro. Speech is transcribed on the
phone by [whisper.cpp](https://github.com/ggml-org/whisper.cpp); nothing is
uploaded.

**[Download the latest APK](../../releases/latest)**

## Requirements

- Android 12 or newer
- A 64-bit ARM phone from roughly 2018 onwards (`arm64-v8a` with `asimdhp` and
  `asimddp`). The app checks at startup and says so plainly if the CPU is older,
  rather than crashing.
- ~150 MB free for the speech model, which the app downloads on first use.

## Setup

1. Install the APK.
2. **Settings → Accessibility → Essential Voice → on.** If the switch is greyed
   out, allow restricted settings first: Settings → Apps → Essential Voice → ⋮.
   This is what lets the app see the key, paste the text, and reach the
   microphone without leaving a permanent notification.
3. Grant *draw over other apps* and the microphone.
4. In the app, **Teach it the key** and press your Essential Key once.

## update.json

`update.json` in this repository is what installed copies read to find out a
newer build exists. Its URL has to stay put, which is why it lives on `main`
rather than being attached to a release.
