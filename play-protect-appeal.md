# Play Protect appeal — draft

Submit at: https://support.google.com/googleplay/android-developer/contact/protectappeals

Be honest that the app uses AccessibilityService and why. Appeals that try to
downplay it do worse than ones that explain it, because the reviewer can read the
manifest either way.

---

**App name:** Essential Voice
**Package:** `com.ishaan.essentialvoice`
**Signing certificate SHA-256:**
`0c737dad0c829dc6773fe362312990d053a6acb882ace955323ca9b4069c0486`
**Distribution:** GitHub Releases —
https://github.com/email2ishaanpatel-collab/essential-voice

**Warning received:** "App blocked to protect your device — This app can request
access to sensitive data. This can increase the risk of identity theft or
financial fraud." Installation is blocked outright, with no option to continue.

---

**What the app does**

Essential Voice is an offline dictation tool for Nothing and CMF phones. Holding
the phone's Essential Key records the user's voice; the recording is transcribed
on the device by whisper.cpp and the resulting text is inserted into the text
field the user was already typing in. It is a speech-to-text input aid.

**Why it declares an AccessibilityService**

Two functions require it, and no other Android API provides either:

1. **Detecting the trigger.** The Essential Key is a hardware key with no entry
   in the device key layout; it reaches apps only as `KEYCODE_UNKNOWN` with
   scancode 250. An AccessibilityService with `flagRequestFilterKeyEvents` is the
   only way an app can observe both the press and the release, which is what
   makes hold-to-talk possible.
2. **Delivering the transcript.** `ACTION_PASTE` on the focused node is how the
   text reaches the field the user was writing in. Without it the app could only
   copy to the clipboard, which is a materially worse experience for a dictation
   tool.

`isAccessibilityTool` is deliberately **not** declared, since the service is an
input aid rather than an assistive technology for users with disabilities.

**Why we believe it is not a threat**

- No audio, no transcript and no telemetry ever leaves the device. Transcription
  is entirely local, using whisper.cpp.
- The app has no background service, no scheduled work beyond a once-daily read
  of a static JSON file on GitHub, and no analytics of any kind.
- Its only network access is downloading the user's chosen speech model from
  huggingface.co and reading that update manifest. It contacts no other host.
- `REQUEST_INSTALL_PACKAGES` was deliberately removed in v1.1. The app cannot
  install software; it links to its release page and the browser does the rest.
- It requests no SMS, no notification-listener and no contacts permissions. Its
  full permission set is `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `VIBRATE`,
  `POST_NOTIFICATIONS`, `INTERNET` and `ACCESS_NETWORK_STATE`.
- The microphone is opened only while the trigger is held and is released
  immediately afterwards; the system microphone indicator is visible throughout.

**What we are asking for**

Review of the automatic block, so that users who deliberately choose to install
this app from its published release page can do so.
