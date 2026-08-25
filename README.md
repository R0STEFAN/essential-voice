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

## "App blocked to protect your device"

You will hit this, and there is no "install anyway" button in it. Here is
exactly what is happening and what works.

Google blocks apps installed **from a browser, messaging app or file manager**
when they declare one of four permissions: `ACCESSIBILITY`,
`NOTIFICATION_LISTENER`, `READ_SMS`, `RECEIVE_SMS`. Those four get abused for
financial fraud, so the block is deliberate and there is no override in the
dialog.

This app needs the accessibility one. It is the only way an app can notice the
Essential Key being held, and it is also what types the transcript back into
whatever you were writing in. There is no version of this that works without it.

Note what is being blocked: **the way you installed it**, not the file. The same
APK installs without complaint through a route that is not a browser.

### The simple way

1. **Play Store → your profile picture → Play Protect → ⚙ (top right)**
2. Turn off **Scan apps with Play Protect**
3. Install the APK
4. **Turn it back on**

Play Protect may later re-scan and offer to remove the app. Decline, or keep
scanning off if that keeps happening.

### Samsung, additionally

Auto Blocker is on by default on One UI 6.1+ and refuses all sideloading on its
own, separately from Play Protect:

**Settings → Security and privacy → Auto Blocker → off**

### If you would rather not touch Play Protect

Install through something that is not a browser. Either works and neither needs
root:

- **adb**: `adb install essential-voice-<version>.apk`
- **[InstallerX Revived](https://github.com/wxxsfxyzm/InstallerX-Revived)** driven
  by **[Shizuku](https://shizuku.rikka.app/)** — Shizuku grants elevated rights
  over wireless debugging, and InstallerX installs through that instead of the
  system installer.

### Is this app worth doing that for?

Decide deliberately. It records your microphone, watches a hardware key, and can
read and write the text field you are focused on. That is genuinely the
permission set of spyware, and you have my word for it and nothing else.

What it does not have is `INTERNET` access to anywhere but GitHub, no analytics,
and no background service. Audio is transcribed on the phone by
[whisper.cpp](https://github.com/ggml-org/whisper.cpp) and discarded. Nothing is
stored and nothing is uploaded.

If that is not good enough, do not install it. That is a reasonable call, and
Play Protect is not wrong to be suspicious.

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
