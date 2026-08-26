package com.ishaan.essentialvoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.Setup

/**
 * Teaches the app which key the Essential Key is.
 *
 * The keycode is not a constant anyone can look up — it is whatever this build
 * of Nothing OS reports — so the only honest way to know it is to have the user
 * press the key once and read what arrives.
 */
@Composable
fun LearnKeyScreen(
    prefs: Prefs,
    accessibilityOn: Boolean,
    onDone: () -> Unit,
) {
    val type = LocalEvType.current
    val context = LocalContext.current
    val (seenKey, seenScan) = prefs.seenKey.collectAsState().value
    val seen = seenKey > 0 || seenScan > 0
    // The Essential Key has no key-layout entry, so it arrives as
    // KEYCODE_UNKNOWN and only the scancode identifies it. Show whichever
    // one actually names the key.
    val ident = if (seenKey > 0) "KEYCODE" else "SCANCODE"
    val value = if (seenKey > 0) seenKey else seenScan

    Column(
        Modifier
            .fillMaxSize()
            .background(EV.Background)
            .padding(EV.PagePadding),
    ) {
        Spacer(Modifier.height(28.dp))
        EvText("Press the", type.display, color = EV.InkMuted)
        EvText("Essential Key", type.display)
        Spacer(Modifier.height(14.dp))
        EvText(
            "One short press is enough. While this screen is open the app is " +
                "watching every hardware key, so press only the one you want.",
            type.sub,
        )

        Spacer(Modifier.height(34.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(if (seen) EV.Yellow else EV.Surface),
            contentAlignment = Alignment.Center,
        ) {
            if (seen) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EvText(ident, type.label, color = EV.Ink)
                    Spacer(Modifier.height(8.dp))
                    EvText("$value", type.display)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(EV.InkFaint),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!accessibilityOn) {
            EvText(
                "The accessibility service is off, so no key will arrive. " +
                    "Turn it on first.",
                type.sub,
                color = EV.Red,
            )
            Spacer(Modifier.height(14.dp))
            EvButton("Open accessibility settings") { Setup.openAccessibilitySettings(context) }
        } else if (!seen) {
            EvText("Waiting for a key…", type.mono)
        } else {
            EvText(
                if (seenKey > 0) {
                    "If that is not the Essential Key, press again — the last key " +
                        "you press is the one that gets saved."
                } else {
                    "Android has no name for this key, so it will be matched by its " +
                        "raw scancode. That is normal for the Essential Key."
                },
                type.sub,
            )
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EvButton("Cancel", kind = EvButtonKind.Quiet, onClick = onDone)
            EvButton(
                "Save this key",
                enabled = seen,
            ) {
                prefs.setTrigger(seenKey, seenScan)
                onDone()
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}
