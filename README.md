# Essential Voice — downloads

Hold-to-talk dictation for the CMF Phone 2 Pro. Speech is transcribed on the
phone by [whisper.cpp](https://github.com/ggml-org/whisper.cpp); nothing is
uploaded.

**[Download the latest APK](../../releases/latest)**

Updates are not automatic and the app cannot install them — it will tell you a
newer build exists and send you here. If you would rather that happened by
itself, point [Obtainium](https://github.com/ImranR98/Obtainium) at this
repository and it will track releases for you.

## Requirements

- Android 12 or newer
- A 64-bit ARM phone from roughly 2018 onwards (`arm64-v8a` with `asimdhp` and
  `asimddp`). The app checks at startup and says so plainly if the CPU is older,
  rather than crashing.
- ~150 MB free for the speech model, which the app downloads on first use.

## Your phone will warn you. Here is why, and what to do

Android blocks sideloaded apps that use an **accessibility service**, and this app
needs one — it is the only way an app can notice the Essential Key being held.
The same service is also what types the text back into whatever you were writing
in. That is a genuinely sensitive permission, so the warning is doing its job;
there is no version of this app that both works and avoids it.

What the app can actually do is listed above and worth checking against the
[source](https://github.com/ggml-org/whisper.cpp) it transcribes with: no audio
and no text ever leaves the phone. The only network request it makes is
downloading the speech model you choose.

If that is not good enough for you, do not install it. That is a reasonable call.

### Samsung — "Blocked by Auto Blocker"

Auto Blocker is on by default on One UI 6.1 and later and refuses all sideloading.

**Settings → Security and privacy → Auto Blocker → off.**

You can turn it back on after installing; the app keeps working.

### "App blocked by Play Protect" / "Unsafe app blocked"

Tap **More details**, then **Install anyway**.

If there is no such option, Play Protect scanning has to come off for the
install:

**Play Store → your profile picture → Play Protect → ⚙ → Scan apps with Play
Protect → off.** Install, then **turn it back on** — it will leave the installed
app alone.

### Chrome — "This file may be harmful"

Open **Downloads**, find the APK, tap the menu next to it and choose **Download
anyway** or **Keep**.

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
