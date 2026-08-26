# Essential Voice

Hold-to-talk dictation for the CMF Phone 2 Pro. A yellow pill appears over
whatever you are in, whisper.cpp transcribes what you said on the phone, and the
text lands in the field you were already typing in. Nothing is uploaded.

The laptop equivalent is the hold-Super-to-talk pill in the Cinnamon
dynamic-island extension; this is the same idea with the Essential Key in place
of Super.

---

## Building

```bash
git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git whisper-src
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`whisper-src/` is deliberately not checked in — it is a pinned upstream checkout,
and CMake fails with a readable message if it is missing. Needs NDK 27.2 and
CMake 3.22 (`sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"`). Only `arm64-v8a` is
built, because only one phone is the target.

## Setting it up on the phone

These cannot be done over adb, because Android will not let a sideloaded app
grant itself any of them. **Reinstalling the app switches the accessibility
service back off** — `Bound services:{}` in `dumpsys accessibility` — so step 1
has to be repeated after every `adb install`:

1. **Settings → Accessibility → Essential Voice → on.** If the switch is greyed
   out, the app was sideloaded: Settings → Apps → Essential Voice → ⋮ → *Allow
   restricted settings*, then come back. `settings put secure
   enabled_accessibility_services …` over adb is silently reverted on Nothing OS
   4 — this has to be the real switch.
2. **Draw over other apps → on.**
3. **Microphone** — the app asks.

Then, in the app: **Teach it the key** and press the Essential Key once.

## Publishing it

```bash
./release.sh          # build the current version
./release.sh 2 1.1    # bump to versionCode 2 / versionName 1.1, then build
```

Both files land in `dist/`, and they go to **two different places**:

| File | Where | Why there |
|---|---|---|
| `essential-voice-<name>.apk` | a GitHub Release | what people download |
| `update.json` | `main` of `publish/` | installed copies read it to learn a newer build exists |

```bash
gh release create v1.1 dist/essential-voice-1.1.apk --repo <owner>/essential-voice
cp dist/update.json publish/ && (cd publish && git commit -am v1.1 && git push)
```

**It has to be an APK.** An `.aab` is a Play Store upload format — Android cannot
install one, so a bundle on a website is a dead link.

### Why the manifest is not on the Framer site

`UPDATE_MANIFEST_URL` is compiled into every build, so it needs a URL that never
moves while its *contents* change every release. Framer cannot do that: uploaded
assets get content-hashed `framerusercontent.com` URLs, so a new `update.json`
would land at a new address and every installed copy would stop finding it. A
`raw.githubusercontent.com` path on `main` is stable and mutable, which is
exactly the combination required.

The site's download button points straight at the release asset. Only the button
lives on Framer; the files do not.

### The signing key

`essential-voice-release.jks` and `keystore.properties`, both gitignored, both
**irreplaceable**. Android refuses an update signed with a different key, so
losing them means every existing install has to be uninstalled before a new
version will go on. Back them up somewhere that is not this laptop.

### Who it will run on

`arm64-v8a` only, and only on a CPU with `asimdhp` and `asimddp` — armv8.2-a, so
roughly 2018 and newer. That is what makes it fast, and it is checked at startup:
an older phone is told plainly instead of taking a SIGILL inside a matrix
multiply. A 32-bit-only phone will not see the app as compatible at all.

## The Essential Key

There is no constant to hardcode. On this phone the key arrives as:

```
/dev/input/event0   "gpio-keys"   KEY_VOLUMEDOWN, 00fa
```

`00fa` is scancode 250, and **no key layout maps it** — `gpio-keys` falls back to
`Generic.kl`, which has no entry — so Android reports it as `KEYCODE_UNKNOWN`
(0). The keycode therefore identifies nothing and the *scancode* is the only
handle on the key. `EssentialKeyService.matchesTrigger` prefers the scancode for
exactly this reason, and the learn screen stores both.

Whether a third-party accessibility service sees the key at all is a property of
this firmware, not of this app: if `NtEssentialKeyImpl` consumes it in the
window-manager policy, nothing downstream — including us — is offered it. The
learn screen answers that question in one press: a number appears, or it does
not.

Two settings on the phone are worth knowing about:

```
nt_block_essential_key               = 1
nt_essential_key_mistouch_prevention = 1
```

### Triggers, in order of preference

| Trigger | Where | Notes |
|---|---|---|
| Essential Key | anywhere | needs the accessibility service; hold or tap |
| Press and hold power | anywhere | via the assistant role — see below |
| Quick Settings tile | anywhere | tap to start, tap to send |
| `TriggerActivity` | shortcut / key remap | launching it toggles dictation |
| Hold-to-talk button | in the app | for checking the setup works |

### The power button

`KEYCODE_POWER` is in `EssentialKeyService.RESERVED` and always will be. The
window manager policy consumes it in `interceptKeyBeforeQueueing`, before
accessibility key filtering is offered anything — which is precisely why a long
press can always force a restart regardless of what an app has done. It cannot
be intercepted, and an app that could intercept it would be a serious problem.

What the system *does* offer is the assistant role. `VoiceAssistService`
registers as a `VoiceInteractionService`, so the app can be chosen as the digital
assistant and reached by "press and hold power button → Digital assistant". The
session draws nothing — `setUiEnabled(false)`, toggle, `hide()` — because the
pill is the whole interface and an assistant scrim would cover the app being
dictated into. `VoiceAssistRecognitionService` is a stub that exists only because
`<voice-interaction-service>` will not validate without a recognition service
named.

The system hands over one launch rather than a key down and up, so this behaves
like Tap mode whatever the trigger setting says. It also takes the gesture away
from Gemini.

`Prefs.triggerMode` switches the key between **hold** (talk while down, release
sends) and **tap** (one press starts, the next sends). Match it to whatever the
Essential Key is configured to do in Nothing OS.

---

## How it fits together

```
EssentialKeyService  accessibility service — sees the key down *and up*, pastes
 (trigger/)          the text, and is what hosts everything else
Dictation            one dictation start to finish: window, mic, transcript.
 (voice/)            A singleton, not a service, so there is no notification
PillView             the pill. Canvas, not views: it repaints per audio buffer
Recorder             AudioRecord at 16kHz straight into a float buffer
WhisperEngine        one whisper context, loaded on the hold, dropped when idle
ModelCatalog         the four tiers and what each costs
MainActivity         the settings app (Compose, white, Geist)
PlacementActivity    drag the real PillView to where it should appear
Prefs / Settings     the store, and an immutable snapshot of it on a StateFlow
```

### Why there is no notification

There is no foreground service, and so nothing in the shade.

The usual route to the microphone is a foreground service of type `microphone`,
which costs a permanent notification and cannot be started from the background
anyway. This app skips it: the system binds an accessibility service with
`BIND_FOREGROUND_SERVICE`, which puts the process at a uid state already allowed
to record. `Dictation` is therefore hosted by `EssentialKeyService`, and the
accessibility service is a hard requirement rather than a nicety — it is also
what pastes the text, so it was required regardless.

The failure mode this trades for is a quiet one: **Android hands a blocked
recorder digital silence rather than an error.** So `Dictation.end` treats a clip
whose peak is exactly zero as a blocked microphone and says so, instead of
shrugging and producing an empty transcript.

### Why the overlay window is small

An overlay that passes touches through has its opacity **capped at 0.8 by the
system** (`MAX_OBSCURING_OPACITY`), which showed up in logcat as:

```
has a system alert window (type = 2038) with FLAG_NOT_TOUCHABLE and
LayoutParams.alpha = 1.00 > 0.80, setting alpha to 0.80 to let touches
pass through
```

A washed-out pill is not the design. So the window is only as big as the pill,
keeps `FLAG_NOT_FOCUSABLE` — the field being typed into must keep input focus or
the text has nowhere to land — and drops `FLAG_NOT_TOUCHABLE`. The intro and
outro are the *window* moving (`slideTo` → `updateViewLayout` per frame), not the
view drawing itself somewhere else, because a view cannot paint outside its own
surface.

---

## The build traps, both of which cost ~17x

Measured, not guessed: `whisper-cli` was cross-compiled for the phone and run on
the app's own recording to separate "the app is slow" from "whisper is slow here".

**1. Architecture flags must be on `CMAKE_C_FLAGS`, not on the `ggml` target.**
ggml builds its kernels in a separate target (`ggml-cpu`) and picks vector paths
from the compiler's view of the architecture. `target_compile_options(ggml …)`
leaves that untouched, and the build reports:

```
CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 0 | DOTPROD = 0
```

**2. Gradle's debug variant appends `-O0` after any per-target `-O3`.**
So ggml — where all the arithmetic happens — was unoptimised. `CMAKE_C_FLAGS_DEBUG`
is overwritten to `-O3 -DNDEBUG` in `cpp/CMakeLists.txt`.

Same 4-second clip, same model, same phone: **26.4s → 13.9s → 0.83s.**

## Measured tiers

`whisper-cli`, 11 seconds of clear speech (`samples/jfk.wav`), 4 threads:

| Tier | Model | Download | Wall time |
|---|---|---|---|
| Fast | `tiny.en` | 78 MB | 1.5 s |
| Balanced | `base.en` | 148 MB | 2.2 s |
| Accurate | `small.en` | 488 MB | 5.8 s |
| Maximum | `small.en`, beam 5 | — | 7.8 s |

Rejected, with numbers:

- `medium.en-q5_0` (539 MB) — **19.6 s in the encoder alone**, 22.0 s total.
- `large-v3-turbo-q5_0` (574 MB) — **32.6 s in the encoder**, 33.9 s total. Neither
  beam size nor 8 threads moved it; the encoder cost is fixed and independent of
  clip length.
- `small.en-q5_1` (190 MB) — *slower* than fp16 `small.en` (7.1 s vs 5.8 s). This
  chip does fp16 natively, so dequantising costs time and only saves disk.

So `small.en` is the practical ceiling here, and "Maximum" is that same model
searched harder — which is also why it needs no extra download.

### Why settings are read through a snapshot

`Prefs` publishes an immutable `Settings` on a `StateFlow`, and the UI reads only
that. Reading SharedPreferences straight from a composable looks like it works
and does not: a plain getter is not a state read, so nothing recomposes when a
value changes and the screen only catches up when the app is reopened. Every
toggle in this app was silently doing that.

### Size

The release build is **3.4 MB**. It was 21.8 MB before three changes, in order of
how much they were worth:

| | Saved |
|---|---|
| R8 (`isMinifyEnabled`) — the dex was 17.7 MB of unreached Compose | ~16 MB |
| Linking ggml statically instead of shipping `libggml-base.so` + `libggml-cpu.so` | ~0.9 MB |
| Dropping five Geist weights the type scale never asks for | ~0.4 MB |

R8 renames aggressively, and JNI resolves by symbol name, so
`proguard-rules.pro` keeps `WhisperLib` and every `native` method verbatim. That
this still holds is checkable rather than hopeable:

```bash
apkanalyzer dex packages --defined-only dist/essential-voice-1.0.apk | grep WhisperLib
llvm-nm -D --defined-only libessentialwhisper.so | grep essentialvoice
```

The two lists have to agree.

### Placement

The pill goes anywhere. Only three columns pull on it — hard against the left
bezel, dead centre, hard against the right — and `snapColumns` derives the edge
ones from the real pill width plus a 10dp margin, so an edge snap sits where the
reference photo puts it rather than at an arbitrary fraction. The placement
screen draws all three, and they light yellow when the pill is on one.

## Carried over from the laptop version

- **Normalise before transcribing.** A phone mic lands speech near a tenth of
  full scale, quiet enough that whisper's own gating returns an empty transcript.
  `Audio.normalise` lifts the peak to 0.55 with the gain capped at 12x, so a
  silent room does not become amplified hiss.
- **Load the model on the hold, not on the release**, so the ~250ms load overlaps
  the sentence instead of being felt after it.
- **Trim the heap after unloading.** bionic keeps freed arenas on its own free
  lists exactly as glibc does; `WhisperLib.nativeTrimHeap` calls
  `mallopt(M_PURGE, 0)` or the memory stays charged to the process.
Deliberately *not* carried over: nothing is written to disk. There is no saved
recording and no transcript history — the text goes to the field it was meant for
and the audio is dropped.

## Why installing shows a warning, and why that is permanent

Play Protect blocks sideloaded apps that declare an accessibility service. That
is a [documented rule](https://developers.google.com/android/play-protect/warning-dev-guidance),
not a heuristic that can be tuned around, and this app cannot drop the service:
it is the only way to see the Essential Key, and it is also what types the text
back. Samsung's Auto Blocker refuses all sideloading separately and has to be
switched off once.

The permission set reads exactly like spyware, for entirely ordinary reasons:

| Permission | Why | How it scans |
|---|---|---|
| accessibility + `flagRequestFilterKeyEvents` | see the key | keylogger |
| `RECORD_AUDIO` | dictation | mic tap |
| `SYSTEM_ALERT_WINDOW` | the pill | overlay phishing |

What *was* removed is `REQUEST_INSTALL_PACKAGES`. An app with the three above
that can also install software is the complete banking-trojan fingerprint, and
self-updating was not worth handing a scanner a fourth reason to object. The
Updates panel now only checks and links out; the install is the browser's job, or
[Obtainium](https://github.com/ImranR98/Obtainium)'s.

`isAccessibilityTool` is deliberately **not** declared. Claiming it while not
serving disabled users is itself a flagged behaviour.

If the block ever looks like a genuine misclassification rather than the policy
working as intended, there is a
[Play Protect appeal](https://support.google.com/googleplay/android-developer/contact/protectappeals).
Note that Play's own policy restricts the Accessibility API to accessibility
purposes, so an appeal is not a formality.

## Updating installed copies

`Updater` reads `UPDATE_MANIFEST_URL` on demand, compares `versionCode` against
the installed one, and opens the release page. It cannot install anything, on
purpose — see the section above. Anyone wanting updates to happen on their own
points Obtainium at the releases page.

The check runs once when the app is opened, so the What's new panel has
something to show without anyone pressing a button, and once a day from the
accessibility service, which is already long-lived — that one notifies at most
once per release. There is no work manager and no polling beyond those two.

### What's new

Two lists, answering different questions.

`WhatsNew.local` ships inside the APK and describes the build it is part of, so
a fresh install can say what it brought with no network. It cannot have
pictures: a picture of a feature is made after the build containing it has been
signed.

`whatsNew` in `update.json` describes the build that is *out*. Entries are
`{ title, body, image }`, all optional but for needing one of `title`/`body`,
and `image` is an https URL — a release asset is the easiest place to put one,
since uploading a picture to a release does not touch the APK. See
`publish/update.json.example`. A malformed entry is skipped rather than failing
the check; a typo in a changelog must not be able to stop the app noticing an
update.

The panel shows the remote list when an update is available (what you would be
getting), the remote list when it matches the installed version (the only way
the installed build's pictures can appear), and `WhatsNew.local` otherwise.
Pictures are fetched by `NetImage`, which caches them in `cacheDir/whatsnew`
and decodes no larger than the screen — an image library would have been
several hundred kilobytes to draw a handful of pictures that never change.

Releasing therefore means two edits: `WhatsNew.local` and `publish/update.json`.

### The debug manifest

`app/src/debug/assets/update-debug.json` is read *instead of* the network by
debug builds only, and its pictures may be `asset:name.png` — files bundled
alongside it. It exists because the panel is otherwise unlookable-at until a
release is cut and pictures are uploaded to it. Release builds never read it;
the file is in the debug source set and is not in the release APK. Delete it to
make a debug build check the real manifest.

## Debugging

```bash
adb logcat -s EVDictation:* EVKey:* EVEngine:* EVWhisper:* EVRecorder:*
```

Note that `sendevent` cannot be used to fake the Essential Key: SELinux denies
the shell user write access to `/dev/input/event0`, so the key has to be pressed
by a finger.
