package com.ishaan.essentialvoice.trigger

import android.app.Activity
import android.os.Bundle
import com.ishaan.essentialvoice.voice.Dictation

/**
 * A door with nothing behind it: opening it toggles dictation and closes again.
 *
 * This exists for the case where the Essential Key cannot be intercepted but
 * *can* be pointed at an app — a shortcut, a gesture, or a Nothing OS key
 * remap. Launching this is then equivalent to tapping the tile.
 */
class TriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Does nothing if the accessibility service is off; there is no
        // microphone to reach without it.
        if (Dictation.isReady) Dictation.toggle()
        finish()
        overridePendingTransition(0, 0)
    }
}
