package com.ishaan.essentialvoice.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ishaan.essentialvoice.R

/**
 * A grey page with near-white cards on it and one loud colour, set in Geist.
 *
 * Deliberately flat: no shadow and no outline anywhere. A card is told apart
 * from the page by being lighter than it, not by being drawn around — which is
 * why [Background] is the darker of the two and [Surface] is almost white.
 * Depth is fill, and the yellow is the only thing allowed to shout.
 */
object EV {
    val Background = Color(0xFFF4F4F4)
    val Surface = Color(0xFFFDFDFD)

    /** Pressed states and progress tracks: a step down from either fill. */
    val SurfaceSunk = Color(0xFFEBEBEB)

    /** Row separators, and nothing else. Not an outline. */
    val Divider = Color(0xFFF0F0F0)

    val Ink = Color(0xFF1B1B1D)
    val InkMuted = Color(0xFF77777C)
    val InkFaint = Color(0xFFA8A8AD)

    /** Text and glyphs that sit on top of [Ink]. */
    val OnInk = Color(0xFFFDFDFD)

    val Yellow = Color(0xFFFFD900)
    val YellowSunk = Color(0xFFE8C500)
    val Red = Color(0xFFD71921)
    val Green = Color(0xFF19A24A)

    val CornerCard = 24.dp
    val CornerRow = 18.dp

    /** Buttons are rounded rectangles, never pills — the radius stays put as
     *  the control gets taller. */
    val CornerButton = 14.dp
    val PagePadding = 20.dp
}

// Only the two weights the type scale actually asks for. Shipping the other
// five cost 380KB to render nothing.
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

class EvTypography {
    val display = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Medium,
        fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp, color = EV.Ink,
    )
    val title = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp, color = EV.Ink,
    )
    val body = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp, color = EV.Ink,
    )
    val sub = TextStyle(
        fontFamily = Geist, fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp, lineHeight = 19.sp, color = EV.InkMuted,
    )
    /** Uppercase mono, used for every section heading and every hard number. */
    val label = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp, color = EV.InkMuted,
    )
    val mono = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp, color = EV.InkMuted,
    )
    val button = TextStyle(
        fontFamily = GeistMono, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, letterSpacing = 1.4.sp, color = EV.Ink,
        textDecoration = TextDecoration.None,
    )
}

val LocalEvType = staticCompositionLocalOf { EvTypography() }

@Composable
fun EssentialVoiceTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEvType provides EvTypography(), content = content)
}
