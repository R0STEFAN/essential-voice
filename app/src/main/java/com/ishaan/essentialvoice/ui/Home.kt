package com.ishaan.essentialvoice.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ishaan.essentialvoice.PlacementActivity
import com.ishaan.essentialvoice.R
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.Settings
import com.ishaan.essentialvoice.Setup
import com.ishaan.essentialvoice.SetupState
import com.ishaan.essentialvoice.Updater
import com.ishaan.essentialvoice.WhatsNew
import com.ishaan.essentialvoice.voice.Dictation
import com.ishaan.essentialvoice.whisper.ModelCatalog
import com.ishaan.essentialvoice.whisper.WhisperEngine
import com.ishaan.essentialvoice.whisper.ModelDownloader
import com.ishaan.essentialvoice.whisper.QualityTier

@Composable
fun HomeScreen(
    setup: SetupState,
    settings: Settings,
    prefs: Prefs,
    download: ModelDownloader.State,
    update: Updater.State,
    onRequestMic: () -> Unit,
    onRequestNotifications: () -> Unit,
    onCheckUpdate: () -> Unit,
    onGetUpdate: (Updater.Release) -> Unit,
    onLearnKey: () -> Unit,
    onDownload: (QualityTier) -> Unit,
    onDeleteModel: (QualityTier) -> Unit,
    onCancelDownload: () -> Unit,
) {
    val context = LocalContext.current
    val type = LocalEvType.current

    Column(
        Modifier
            .fillMaxSize()
            .background(EV.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EV.PagePadding)
            .padding(bottom = 48.dp),
    ) {
        Masthead(setup)

        // ---- updates -------------------------------------------------------
        SectionLabel("Updates")
        Panel {
            UpdatePanel(update, onCheck = onCheckUpdate, onGet = onGetUpdate)
            WhatsNewSection(update, settings, prefs)
        }

        // ---- setup ---------------------------------------------------------
        if (!setup.ready) {
            SectionLabel("Set up")
            Panel {
                PermissionRow(
                    "Accessibility service",
                    "Sees the Essential Key, puts the text back where you were typing, " +
                        "and is what lets the app reach the microphone without leaving " +
                        "a notification behind.",
                    setup.accessibility,
                ) { Setup.openAccessibilitySettings(context) }
                Hairline()
                PermissionRow(
                    "Draw over other apps",
                    "So the pill can appear on top of whatever you are in.",
                    setup.overlay,
                ) { Setup.openOverlaySettings(context) }
                Hairline()
                PermissionRow(
                    "Microphone",
                    "Recording never leaves the phone.",
                    setup.microphone,
                    onFix = onRequestMic,
                )
            }
        }

        if (!WhisperEngine.isSupported) {
            SectionLabel("Not supported")
            Panel(fill = EV.Surface) {
                Column(Modifier.padding(18.dp)) {
                    EvText(
                        "This phone's processor is missing the half-precision and " +
                            "dot-product instructions this build needs, so speech " +
                            "recognition cannot run on it.",
                        type.body,
                        color = EV.Red,
                    )
                }
            }
        }

        // ---- try it --------------------------------------------------------
        SectionLabel("Try it")
        TryItPanel(ready = setup.canDictate)

        // ---- the key -------------------------------------------------------
        SectionLabel("The key")
        Panel {
            SettingRow(
                title = if (setup.keyLearned) "Essential Key" else "Teach it the key",
                sub = if (setup.keyLearned) {
                    "Learned. Use it anywhere."
                } else {
                    "Press your Essential Key once so the app knows which key it is."
                },
                enabled = setup.accessibility,
                onClick = onLearnKey,
            ) {
                if (setup.keyLearned) {
                    EvText(
                        if (settings.triggerKeyCode > 0) "KEY ${settings.triggerKeyCode}"
                        else "SCAN ${settings.triggerScanCode}",
                        type.mono,
                        color = EV.Ink,
                    )
                } else {
                    EvText("SET", type.button, color = EV.Ink)
                }
            }
            Hairline()
            Column(Modifier.padding(18.dp)) {
                EvText("How the key works", type.body)
                Spacer(Modifier.height(4.dp))
                EvText(
                    if (settings.triggerMode == Prefs.MODE_TAP) {
                        "One press starts listening, the next one sends. " +
                            "Match this to an Essential Key set to a single tap."
                    } else {
                        "Talk while the key is down. Letting go sends."
                    },
                    type.sub,
                )
                Spacer(Modifier.height(12.dp))
                EvSegmented(
                    options = listOf(Prefs.MODE_HOLD to "Hold", Prefs.MODE_TAP to "Tap"),
                    selectedId = settings.triggerMode,
                ) { prefs.setTriggerMode(it) }
            }
            Hairline()
            SettingRow(
                title = "Take over the key",
                sub = "Stops Essential Space also firing while you dictate.",
            ) {
                EvSwitch(settings.consumeKey) { prefs.setConsumeKey(it) }
            }
            if (settings.triggerMode == Prefs.MODE_HOLD) {
                Hairline()
                StepperRow(
                    title = "Hold before it listens",
                    sub = "A shorter press does nothing at all.",
                    value = settings.holdMs,
                    suffix = "ms",
                    step = 40,
                    range = 100..600,
                ) { prefs.setHoldMs(it) }
            }
            Hairline()
            // Folded away because it is the answer to a question most people
            // never ask — they have the key, so the power button is noise until
            // the moment they do not.
            Disclosure("Don't have the Essential Key? Use your power button.") {
                SettingRow(
                    title = "Press and hold power",
                    sub = "Hold the power button briefly to start recording. Hold it " +
                        "briefly again to stop and send. It is two presses, not a " +
                        "press-and-talk \u2014 the assistant gesture gives the app a " +
                        "single signal each time, with no release to listen for.",
                    onClick = { Setup.openAssistantSettings(context) },
                ) { EvText("SET UP", type.button, color = EV.Ink) }
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                    EvText(
                        "1.  Set Essential Voice as the digital assistant app.\n" +
                            "2.  Set \u201cpress and hold power\u201d to open it.\n\n" +
                            "This replaces Gemini on that gesture.",
                        type.mono,
                    )
                }
            }
        }

        // ---- quality -------------------------------------------------------
        SectionLabel("Recognition quality")
        EvText(
            "Bigger models hear more of what you actually said, and cost a one-off " +
                "download plus a longer wait afterwards. Every timing below was " +
                "measured on this phone. Everything runs on it too.",
            type.sub,
            Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp),
        )
        // Sideways, same as What's new: three cards stacked took most of a
        // screen to say one thing. IntrinsicSize.Max keeps them level, which a
        // lazy row could not do.
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .height(IntrinsicSize.Max)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ModelCatalog.tiers.forEach { tier ->
                TierCard(
                    modifier = Modifier.width(268.dp).fillMaxHeight(),
                    tier = tier,
                    selected = settings.qualityTier == tier.id,
                    installed = tier.isInstalled(context),
                    download = download,
                    onSelect = {
                        prefs.setQualityTier(tier.id)
                        Dictation.onTierChanged()
                    },
                    onDownload = { onDownload(tier) },
                    onDelete = { onDeleteModel(tier) },
                    onCancel = onCancelDownload,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        StorageLine(context)

        // ---- language ------------------------------------------------------
        SectionLabel("Language")
        Panel {
            Column(Modifier.padding(18.dp)) {
                EvText("Dictation language", type.body)
                Spacer(Modifier.height(4.dp))
                EvText("Auto detects speech language and handles mixed speech / surzhyk.", type.sub)
                Spacer(Modifier.height(12.dp))
                EvSegmented(
                    options = listOf(
                        Prefs.LANG_AUTO to "Auto",
                        Prefs.LANG_UK to "Українська",
                        Prefs.LANG_EN to "English",
                    ),
                    selectedId = settings.language,
                ) { prefs.setLanguage(it) }
            }
        }

        // ---- placement -----------------------------------------------------
        SectionLabel("Where the pill appears")
        Panel(padding = PaddingValues(bottom = 4.dp)) {
            PlacementPreview(settings)
            SettingRow(
                title = "Place it yourself",
                sub = "Drag it anywhere. It snaps to either edge and to the centre.",
                onClick = {
                    context.startActivity(
                        Intent(context, PlacementActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ) { EvText("OPEN", type.button, color = EV.Ink) }
            Hairline()
            Column(Modifier.padding(18.dp)) {
                EvText("Slides in from", type.body)
                Spacer(Modifier.height(4.dp))
                EvText("Which edge the intro and outro travel through.", type.sub)
                Spacer(Modifier.height(12.dp))
                EvSegmented(
                    options = listOf(
                        "auto" to "Nearest",
                        "left" to "Left",
                        "right" to "Right",
                    ),
                    selectedId = settings.slideFrom,
                ) { prefs.setSlideFrom(it) }
            }
        }

        // ---- behaviour -----------------------------------------------------
        SectionLabel("Behaviour")
        Panel {
            SettingRow(
                title = "Type into the field",
                sub = "Puts the words straight where the cursor was.",
            ) {
                EvSwitch(settings.typeIntoField) { prefs.setTypeIntoField(it) }
            }
            Hairline()
            SettingRow(
                title = "Copy to clipboard",
                sub = if (settings.typeIntoField) {
                    "Also keeps a copy, so you can paste it somewhere else."
                } else {
                    "The only place the words go, with typing switched off."
                },
            ) {
                EvSwitch(settings.copyToClipboard) { prefs.setCopyToClipboard(it) }
            }
            if (!settings.typeIntoField && !settings.copyToClipboard) {
                Hairline()
                Column(Modifier.padding(18.dp)) {
                    EvText(
                        "With both off there is nowhere for the words to go, so they " +
                            "will just be shown to you and then forgotten.",
                        type.sub,
                        color = EV.Red,
                    )
                }
            }
            Hairline()
            SettingRow(
                title = "Haptics",
                sub = "A tick when it starts listening and when the text lands.",
            ) {
                EvSwitch(settings.haptics) { on ->
                    prefs.setHaptics(on)
                    // Switching it on should be something you feel, not something
                    // you have to go and test.
                    if (on) Dictation.buzz(context, 22)
                }
            }
            Hairline()
            SettingRow(
                title = "Tell me about new versions",
                sub = "Checks once a day and notifies you once per release. " +
                    "Nothing is sent \u2014 it only reads a small file.",
            ) {
                EvSwitch(settings.updateNotices) { on ->
                    prefs.setUpdateNotices(on)
                    if (on && !Setup.hasNotificationPermission(context)) onRequestNotifications()
                }
            }
            Hairline()
            StepperRow(
                title = "Drop the model after",
                sub = "Idle time before the model leaves memory. It reloads while you speak.",
                value = settings.idleUnloadSeconds,
                suffix = "s",
                step = 60,
                range = 0..1800,
            ) { prefs.setIdleUnloadSeconds(it) }
        }

        // ---- ideas ---------------------------------------------------------
        SectionLabel("Ideas")
        FeedbackPanel()

        // ---- support -------------------------------------------------------
        SectionLabel("Support me")
        SupportPanel()

        Spacer(Modifier.height(28.dp))
        EvText(
            "Speech is transcribed by whisper.cpp on this phone. Nothing is uploaded, " +
                "no recording is kept, and the only thing the app ever downloads is the " +
                "model you pick.",
            type.mono,
            Modifier.padding(horizontal = 4.dp),
        )
    }
}

// ---- pieces --------------------------------------------------------------

/**
 * Press and hold this to run exactly the dictation the key runs. It is the way
 * to check the microphone, the model and the pill without reaching for the key.
 */
@Composable
private fun TryItPanel(ready: Boolean) {
    val type = LocalEvType.current
    var held by remember { mutableStateOf(false) }

    val fill = when {
        !ready -> EV.SurfaceSunk
        held -> EV.InkMuted
        else -> EV.Ink
    }

    Panel(fill = EV.Surface) {
        Column(Modifier.padding(18.dp)) {
            EvText(
                if (ready) "Hold the button and say something."
                else "Finish the setup above first.",
                type.sub,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(EV.CornerButton))
                    .background(fill)
                    .then(
                        if (ready) {
                            Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    // Consume everything for the whole press.
                                    // Without this the surrounding scroller claims
                                    // the smallest drift and cancels the hold
                                    // halfway through a sentence.
                                    awaitFirstDown(requireUnconsumed = false).consume()
                                    held = true
                                    Dictation.begin()
                                    try {
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            ev.changes.forEach { it.consume() }
                                            if (ev.changes.none { it.pressed }) break
                                        }
                                    } finally {
                                        held = false
                                        Dictation.end()
                                    }
                                }
                            }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                EvText(
                    if (held) "LISTENING — LET GO TO SEND" else "HOLD TO TALK",
                    type.button,
                    // Yellow only while it is actually listening: the one place
                    // the accent still means "this is happening now".
                    color = when {
                        !ready -> EV.InkFaint
                        held -> EV.Yellow
                        else -> EV.OnInk
                    },
                )
            }
        }
    }
}

/**
 * There is no store behind this app, so checking for a new build is something
 * the app has to offer for itself.
 */
@Composable
private fun UpdatePanel(
    state: Updater.State,
    onCheck: () -> Unit,
    onGet: (Updater.Release) -> Unit,
) {
    val type = LocalEvType.current
    val context = LocalContext.current
    val installed = remember { Updater.installedVersionName(context) }
    val code = remember { Updater.installedVersionCode(context) }

    Column(Modifier.padding(18.dp)) {
        EvText("VERSION $installed  ·  BUILD $code", type.label, color = EV.Ink)
        Spacer(Modifier.height(10.dp))

        when (state) {
            is Updater.State.Available -> {
                EvText("Version ${state.release.versionName} is out.", type.body)
                if (state.release.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    EvText(state.release.notes, type.sub)
                }
            }
            is Updater.State.Checking -> EvText("Checking…", type.sub)
            is Updater.State.UpToDate -> EvText("This is the newest build.", type.sub)
            is Updater.State.Failed -> EvText(state.message, type.sub, color = EV.Red)
            Updater.State.Idle -> EvText("Not checked yet.", type.sub)
        }

        Spacer(Modifier.height(14.dp))
        if (state is Updater.State.Available) {
            EvButton("Open the release page") { onGet(state.release) }
        } else {
            EvButton("Check now", kind = EvButtonKind.Quiet, onClick = onCheck)
        }

        Spacer(Modifier.height(12.dp))
        EvText(
            "Checking only reads a small file and opens a page. It cannot install " +
                "anything — deliberately. For updates that happen on their own, " +
                "point Obtainium at the releases page.",
            type.mono,
        )
    }
}

/**
 * What changed, in a handful of lines.
 *
 * Which build it describes depends on what the check found. If there is a newer
 * one, this is a look at what you would be getting; if there is not, it is the
 * receipt for what you already have. The heading always names the version, so
 * the two are never mistaken for each other.
 *
 * Pictures only ever come from the manifest — see [WhatsNew] for why the built-in
 * list cannot have them.
 */
@Composable
private fun WhatsNewSection(state: Updater.State, settings: Settings, prefs: Prefs) {
    val type = LocalEvType.current
    val context = LocalContext.current
    val installed = remember { Updater.installedVersionName(context) }
    val installedCode = remember { Updater.installedVersionCode(context) }

    val release = when (state) {
        is Updater.State.Available -> state.release
        is Updater.State.UpToDate -> state.release
        else -> null
    }

    // An update that says nothing about itself still has its one-line `notes`,
    // which is better than an empty section.
    val remote = release?.whatsNew.orEmpty().ifEmpty {
        val notes = release?.notes?.trim().orEmpty()
        if (notes.isBlank()) emptyList()
        else listOf(WhatsNew.Item("In this release", notes))
    }

    val available = state is Updater.State.Available
    val items = when {
        available -> remote
        // Up to date: the manifest is describing the build already installed,
        // so prefer it — it is the only version of the list with pictures.
        remote.isNotEmpty() && release?.versionName == installed -> remote
        else -> WhatsNew.local
    }
    if (items.isEmpty()) return

    val version = if (available) release?.versionName ?: "?" else installed
    // What closing it means: this list, for this build. A newer one brings the
    // section back rather than being silently swallowed by an old dismissal.
    val shownFor = if (available) release?.versionCode ?: installedCode else installedCode
    val heading = if (available) "WHAT'S NEW IN $version" else "WHAT'S NEW  \u00b7  $version"

    Hairline()

    // Closed, it collapses to a pill rather than vanishing — a cross that made
    // the thing unreachable would be a trapdoor, not a close button.
    if (settings.dismissedWhatsNewFor >= shownFor) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp)) {
            ReopenPill(heading) { prefs.dismissedWhatsNewFor = 0 }
        }
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 10.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EvText(
            heading,
            type.label,
            Modifier.weight(1f).padding(top = 8.dp),
            color = EV.Ink,
        )
        CloseButton { prefs.dismissedWhatsNewFor = shownFor }
    }
    Spacer(Modifier.height(12.dp))

    // One card per entry, side by side.
    //
    // Not a lazy row: there are only ever a handful of these, and a lazy layout
    // cannot measure intrinsics — which is exactly what makes every card here
    // the height of the tallest one instead of a ragged edge.
    //
    // The inset is padding *inside* the scroller, so the first and last cards
    // sit level with the heading and the row still scrolls edge to edge rather
    // than stopping short of it.
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .height(IntrinsicSize.Max)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { item ->
            WhatsNewCard(item, Modifier.fillMaxHeight())
        }
    }
    Spacer(Modifier.height(18.dp))
}

/** What the closed section leaves behind: a tap target that brings it back. */
@Composable
private fun ReopenPill(label: String, onClick: () -> Unit) {
    val type = LocalEvType.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) EV.SurfaceSunk else EV.Background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(EV.Yellow),
        )
        Spacer(Modifier.width(9.dp))
        EvText(label, type.label, color = EV.Ink, maxLines = 1)
    }
}

/**
 * A heading that opens to show what is under it.
 *
 * Used for the things that are only relevant to some people: shown closed, they
 * cost one line instead of a screen, and nobody who does not need them has to
 * scroll past them.
 */
@Composable
private fun Disclosure(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val type = LocalEvType.current
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rotation by animateFloatAsState(if (open) 90f else 0f, label = "chevron")

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (pressed) EV.SurfaceSunk else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null) { open = !open }
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvText(title, type.body, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Canvas(Modifier.size(10.dp).rotate(rotation)) {
                // A chevron, pointing right when closed and down when open.
                val w = 1.6.dp.toPx()
                drawLine(EV.InkMuted, Offset(size.width * 0.25f, 0f),
                    Offset(size.width * 0.75f, size.height / 2f), w, StrokeCap.Round)
                drawLine(EV.InkMuted, Offset(size.width * 0.75f, size.height / 2f),
                    Offset(size.width * 0.25f, size.height), w, StrokeCap.Round)
            }
        }
        if (open) content()
    }
}

@Composable
private fun WhatsNewCard(item: WhatsNew.Item, modifier: Modifier = Modifier) {
    val type = LocalEvType.current

    Column(
        modifier
            // Narrow enough that the next card shows at the edge of the screen,
            // which is the only thing telling anyone the row scrolls.
            .width(248.dp)
            .clip(RoundedCornerShape(EV.CornerRow))
            .background(EV.Surface)
            .padding(14.dp),
    ) {
        if (item.image != null) {
            NetImageBox(
                item.image,
                Modifier
                    .fillMaxWidth()
                    .height(132.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        if (item.title.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(EV.Yellow),
                )
                Spacer(Modifier.width(9.dp))
                EvText(item.title, type.body)
            }
        }
        if (item.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            EvText(item.body, type.sub)
        }
    }
}

/**
 * The cross that closes a panel.
 *
 * Drawn rather than set as a × glyph: at this size a typeface's multiplication
 * sign sits slightly high and slightly left of the box it is centred in, and
 * two lines are exact. Press changes colour and nothing moves, like everything
 * else here.
 */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val stroke = if (pressed) EV.Ink else EV.InkFaint

    Box(
        Modifier
            // 40dp of target around a 12dp mark: the cross is small on purpose,
            // and something this easy to hit by accident must not be.
            .size(40.dp)
            .clip(CircleShape)
            .background(if (pressed) EV.SurfaceSunk else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val w = 1.6.dp.toPx()
            drawLine(stroke, Offset(0f, 0f), Offset(size.width, size.height), w, StrokeCap.Round)
            drawLine(stroke, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
        }
    }
}

/**
 * A box to type an idea into, and a button that hands it to a mail app.
 *
 * There is no server behind this app and adding one for a suggestion box would
 * mean running something, paying for it, and holding other people's messages.
 * So this composes an email instead: the text goes into a draft addressed to
 * me, in whatever mail app the phone already has, and the person sending it can
 * see exactly what is being sent and press send themselves. Nothing leaves the
 * phone until they do.
 *
 * Which also means this cannot fail silently in the way a form post can — if no
 * mail app answers, the text goes to the clipboard rather than nowhere.
 */
@Composable
private fun FeedbackPanel() {
    val type = LocalEvType.current
    val context = LocalContext.current
    var idea by rememberSaveable { mutableStateOf("") }
    val canSend = idea.isNotBlank()

    Panel {
        Column(Modifier.padding(18.dp)) {
            EvText("Got any ideas or improvements?", type.title)
            Spacer(Modifier.height(4.dp))
            EvText(
                "I can make them possible. Write it here and it opens an email to " +
                    "me — you send it, so you can see exactly what goes.",
                type.sub,
            )
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 116.dp)
                    .clip(RoundedCornerShape(EV.CornerRow))
                    .background(EV.Background)
                    .padding(14.dp),
            ) {
                if (idea.isEmpty()) {
                    EvText("Something that would make this better…", type.body, color = EV.InkFaint)
                }
                BasicTextField(
                    value = idea,
                    onValueChange = { idea = it },
                    // Has to be as tall as the box it is drawn in. Sized to its
                    // own one line of text it looked like a text area and
                    // behaved like a single line: a tap anywhere below the
                    // first line missed it and nothing focused.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp),
                    textStyle = type.body,
                    cursorBrush = SolidColor(EV.Ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }

            Spacer(Modifier.height(14.dp))
            EvButton("Send it to me", Modifier.fillMaxWidth(), enabled = canSend) {
                if (sendIdea(context, idea)) idea = ""
            }
            Spacer(Modifier.height(10.dp))
            EvText(
                "Goes to $FEEDBACK_EMAIL. The build number and phone model are added " +
                    "at the bottom of the draft so I know what I am looking at — " +
                    "delete them if you would rather not.",
                type.mono,
            )
        }
    }
}

private const val FEEDBACK_EMAIL = "email2ishaanpatel@gmail.com"

/**
 * Opens a mail draft. Returns true if something took it.
 *
 * The subject and body go **in the mailto URI**, not in EXTRA_SUBJECT and
 * EXTRA_TEXT. With ACTION_SENDTO the extras are advisory and Gmail drops them,
 * which is why the first version of this opened a correctly addressed but
 * completely empty draft. The query string is the part every mail client reads.
 * The extras are set as well, for the ones that only read those.
 *
 * `resolveActivity` is not used to check first: that needs a `<queries>` entry
 * for package visibility, and trying and catching answers the same question
 * without one.
 */
private fun sendIdea(context: Context, idea: String): Boolean {
    val version = runCatching { Updater.installedVersionName(context) }.getOrDefault("?")
    val code = runCatching { Updater.installedVersionCode(context) }.getOrDefault(0)
    val subject = "Essential Voice — an idea"
    val body = buildString {
        append(idea.trim())
        append("\n\n—\n")
        append("Essential Voice $version (build $code)\n")
        append("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ")
        append("Android ${android.os.Build.VERSION.RELEASE}")
    }

    // Uri.encode, not Uri.Builder: the builder would encode the "?" and "&" of
    // the query itself and hand the mail app one long address.
    val uri = Uri.parse(
        "mailto:" + Uri.encode(FEEDBACK_EMAIL) +
            "?subject=" + Uri.encode(subject) +
            "&body=" + Uri.encode(body),
    )

    val mail = Intent(Intent.ACTION_SENDTO, uri)
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)

    return runCatching {
        context.startActivity(mail)
        true
    }.getOrElse {
        // No mail app. Losing what someone just typed is the one unacceptable
        // outcome, so it goes somewhere they can paste it from.
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("Essential Voice idea", body))
        Toast.makeText(
            context,
            "No email app here — the idea is on your clipboard. Send it to $FEEDBACK_EMAIL.",
            Toast.LENGTH_LONG,
        ).show()
        false
    }
}

/**
 * The only link in the app that asks for anything.
 *
 * It sits at the bottom, it is a link rather than a purchase, and nothing in the
 * app is behind it — the whole thing works the same whether or not anyone ever
 * presses it. That is the deal, so it is worth saying out loud here.
 */
@Composable
private fun SupportPanel() {
    val type = LocalEvType.current
    val context = LocalContext.current
    val open = { openLink(context, PAYPAL_URL) }

    Panel {
        Column(Modifier.padding(18.dp)) {
            EvText(
                "This is free, has no account, no ads and no analytics, and it is " +
                    "going to stay that way.",
                type.body,
            )
            Spacer(Modifier.height(4.dp))
            EvText(
                "If it has saved you some typing, you can send something over. " +
                    "Nothing changes in the app either way.",
                type.sub,
            )
            Spacer(Modifier.height(14.dp))
            EvButton("Support me on PayPal", Modifier.fillMaxWidth(), onClick = open)
            Spacer(Modifier.height(8.dp))
            EvText("paypal.me/ishaanpatel19", type.mono, Modifier.padding(start = 4.dp))
        }

        Hairline()

        Column(Modifier.padding(18.dp)) {
            EvText("THE FUND", type.label, color = EV.Ink)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    // The card's own fill, not a sunk tile: the photo has a
                    // near-white background of its own, and anything darker
                    // behind it shows as a rectangle around the headphones.
                    .background(EV.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.headphone_one),
                    contentDescription = "Nothing Headphone (1)",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.height(14.dp))
            EvText("Help me buy the Nothing Headphone (1)", type.body)
            Spacer(Modifier.height(4.dp))
            EvText(
                "This is what the tip jar is actually for. I have been listening " +
                    "to my own voice through the phone speaker for weeks.",
                type.sub,
            )
            Spacer(Modifier.height(14.dp))
            // Kept short on purpose: the button label is mono, uppercased and
            // letterspaced, and a longer one is clipped on a narrow phone.
            EvButton(
                "Chip in for these",
                Modifier.fillMaxWidth(),
                kind = EvButtonKind.Quiet,
                onClick = open,
            )
        }
    }
}

private const val PAYPAL_URL = "https://paypal.me/ishaanpatel19"

/** Hands a URL to a browser. Nothing in this app opens a payment page itself. */
private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun Masthead(setup: SetupState) {
    val type = LocalEvType.current
    Row(
        Modifier.fillMaxWidth().padding(top = 34.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            DotMark()
            Spacer(Modifier.height(18.dp))
            // One line, one colour. The name is the name; it is not two words
            // of different importance.
            EvText("Essential Voice", type.display, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            EvText("Software for all", type.sub)
        }
        Spacer(Modifier.width(12.dp))
        StatusChip(setup.ready)
    }
}

/**
 * Whether the thing is actually on, in the corner where a status belongs.
 *
 * Green rather than the app's yellow: yellow here is the colour of things you
 * can press, and this is not one — it reports, it does not act.
 */
@Composable
private fun StatusChip(ready: Boolean) {
    val type = LocalEvType.current
    Row(
        Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(EV.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ready) EV.Green else EV.InkFaint),
        )
        Spacer(Modifier.width(8.dp))
        EvText(
            if (ready) "ENABLED" else "SET UP",
            type.label,
            color = EV.Ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun DotMark() {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { col ->
                    val on = row == 1 || col == 1
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (on) EV.Yellow else EV.SurfaceSunk),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, sub: String, granted: Boolean, onFix: () -> Unit) {
    SettingRow(title = title, sub = sub, onClick = if (granted) null else onFix) {
        if (granted) StatusPip(true, "On")
        else EvText("GRANT", LocalEvType.current.button, color = EV.Ink)
    }
}

@Composable
private fun StepperRow(
    title: String,
    sub: String,
    value: Int,
    suffix: String,
    step: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    val type = LocalEvType.current
    SettingRow(title = title, sub = sub) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("–", enabled = value - step >= range.first) {
                onChange((value - step).coerceIn(range.first, range.last))
            }
            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                EvText(
                    if (value == 0 && suffix == "s") "NEVER" else "$value$suffix",
                    type.mono,
                    color = EV.Ink,
                    maxLines = 1,
                )
            }
            StepButton("+", enabled = value + step <= range.last) {
                onChange((value + step).coerceIn(range.first, range.last))
            }
        }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        !enabled -> EV.SurfaceSunk
        pressed -> EV.Ink
        else -> EV.Background
    }
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(fill)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interaction, indication = null, onClick = onClick,
                    )
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        EvText(
            glyph,
            LocalEvType.current.body,
            color = when {
                !enabled -> EV.InkFaint
                pressed -> EV.OnInk
                else -> EV.Ink
            },
        )
    }
}

/** A miniature of the screen showing where the pill will land. */
@Composable
private fun PlacementPreview(settings: Settings) {
    val type = LocalEvType.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(18.dp)
            .height(210.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(EV.SurfaceSunk),
    ) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            Box(
                Modifier
                    .offsetFraction(settings.pillX, settings.pillY)
                    .width(52.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(EV.Yellow),
            )
        }
        EvText(
            "%d%% ACROSS · %d%% DOWN".format(
                (settings.pillX * 100).toInt(), (settings.pillY * 100).toInt(),
            ),
            type.label,
            Modifier.align(Alignment.BottomStart).padding(14.dp),
        )
    }
}

/** Places a child at a fraction of the parent, centred on that point. */
private fun Modifier.offsetFraction(fx: Float, fy: Float): Modifier =
    this.then(
        layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(constraints.maxWidth, constraints.maxHeight) {
                val px = (constraints.maxWidth * fx - placeable.width / 2f).toInt()
                    .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                val py = (constraints.maxHeight * fy - placeable.height / 2f).toInt()
                    .coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                placeable.place(px, py)
            }
        },
    )

@Composable
private fun StorageLine(context: Context) {
    val used = ModelCatalog.installedBytes(context) / 1_000_000
    EvText(
        if (used == 0L) "No models downloaded yet." else "$used MB of models on this phone.",
        LocalEvType.current.mono,
        Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun TierCard(
    modifier: Modifier = Modifier,
    tier: QualityTier,
    selected: Boolean,
    installed: Boolean,
    download: ModelDownloader.State,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val type = LocalEvType.current
    val running = download as? ModelDownloader.State.Running
    val failed = download as? ModelDownloader.State.Failed
    val isDownloading = running?.tierId == tier.id
    val thisFailed = failed?.tierId == tier.id

    Panel(
        modifier = modifier,
        fill = if (selected) EV.Yellow else EV.Surface,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EvText(tier.label, type.title)
                        if (installed) {
                            Spacer(Modifier.width(9.dp))
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) EV.Ink else EV.Yellow),
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    EvText(
                        tier.sub,
                        type.sub,
                        color = if (selected) EV.Ink.copy(alpha = 0.72f) else EV.InkMuted,
                    )
                }
                Spacer(Modifier.width(12.dp))
                SelectDot(selected = selected, enabled = installed, onClick = onSelect)
            }

            Spacer(Modifier.height(14.dp))
            EvText(
                "${tier.sizeMb} MB   ·   ~${tier.waitLabel} FOR 10s OF SPEECH",
                type.label,
                color = if (selected) EV.Ink.copy(alpha = 0.66f) else EV.InkMuted,
            )

            if (isDownloading) {
                Spacer(Modifier.height(14.dp))
                EvProgress(running.fraction)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EvText(
                        "${running.done / 1_000_000} / ${tier.sizeMb} MB",
                        type.mono,
                        Modifier.weight(1f),
                    )
                    EvButton("Cancel", kind = EvButtonKind.Quiet, onClick = onCancel)
                }
            } else {
                if (thisFailed) {
                    Spacer(Modifier.height(10.dp))
                    EvText(failed.message, type.mono, color = EV.Red)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!installed) {
                        EvButton(
                            if (thisFailed) "Retry" else "Download",
                            kind = if (selected) EvButtonKind.Quiet else EvButtonKind.Primary,
                            onClick = onDownload,
                        )
                    } else {
                        if (!selected) EvButton("Use this", onClick = onSelect)
                        EvButton("Delete", kind = EvButtonKind.Danger, onClick = onDelete)
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectDot(selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            // Unselected was an outline around nothing, so with outlines gone
            // it needs a fill — otherwise the radio you can press is invisible
            // and only the one you already chose is on screen.
            .background(if (selected) EV.Ink else EV.SurfaceSunk)
            .then(
                if (enabled && !selected) {
                    Modifier.clickable(
                        interactionSource = interaction, indication = null, onClick = onClick,
                    )
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(EV.Yellow))
        }
    }
}
