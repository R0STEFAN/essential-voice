package com.ishaan.essentialvoice.trigger

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.PersistableBundle
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.UpdateNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.ishaan.essentialvoice.voice.Dictation

/**
 * Watches the hardware keys and turns a *held* Essential Key into a dictation.
 *
 * An accessibility service is the only way a third-party app gets to see a
 * hardware key before the app in focus does, and — critically — the only way to
 * see the key *release*, which is what a hold-to-talk gesture is made of. It is
 * also what puts the finished text into the field the user was already typing in.
 */
class EssentialKeyService : AccessibilityService() {

    companion object {
        private const val TAG = "EVKey"

        /**
         * How long to leave the transcript on the clipboard before taking it
         * back. ACTION_PASTE returns before the target app has read the clip,
         * so clearing immediately pastes nothing.
         */
        private const val CLIPBOARD_RELEASE_MS = 2500L

        @Volatile var instance: EssentialKeyService? = null
            private set

        val isRunning: Boolean get() = instance != null

        /**
         * Keys the system relies on. Never swallowed, and never offered as a
         * trigger, however tempting it is to bind volume-down.
         */
        private val RESERVED = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_SLEEP,
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs

    /** Set between the hold firing and the key coming back up. */
    private var holding = false
    private var downAt = 0L
    private var pendingStart: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs.get(this)
        // This service is also the app's host: being bound by the system is what
        // lets it open the microphone without a foreground service, and so
        // without a permanent notification.
        Dictation.attach(this)
        // Cheap, once a day, and the only thing that will ever remind the user a
        // new build exists — nobody opens a settings screen to go looking.
        scope.launch { UpdateNotice.checkIfDue(this@EssentialKeyService) }
        Log.i(TAG, "connected")
    }

    override fun onDestroy() {
        scope.cancel()
        Dictation.detach()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---- the key --------------------------------------------------------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // Learn mode: report whatever arrives so the app can show which key this
        // build of Nothing OS actually produces.
        //
        // Both halves matter. The Essential Key has no entry in the key layout,
        // so the framework reports KEYCODE_UNKNOWN (0) and the *scancode* — 250
        // on this phone — is the only thing that names it.
        if (prefs.learnMode) {
            if (event.action == KeyEvent.ACTION_DOWN && code !in RESERVED) {
                prefs.reportKey(code, event.scanCode)
                Log.i(TAG, "learn: keyCode=$code scan=${event.scanCode} dev=${event.device?.name}")
                return true
            }
            return false
        }

        if (code in RESERVED) return false
        if (!matchesTrigger(event)) return false
        val settings = prefs.now

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return settings.consumeKey
                downAt = event.eventTime
                if (settings.triggerMode == Prefs.MODE_TAP) return settings.consumeKey

                pendingStart?.let { handler.removeCallbacks(it) }
                val r = Runnable {
                    holding = true
                    startDictation()
                }
                pendingStart = r
                handler.postDelayed(r, settings.holdMs.toLong())
            }

            KeyEvent.ACTION_UP -> {
                pendingStart?.let { handler.removeCallbacks(it) }
                pendingStart = null

                if (settings.triggerMode == Prefs.MODE_TAP) {
                    // One press starts, the next stops. Matches an Essential Key
                    // configured to fire on a single tap.
                    Dictation.toggle()
                    return settings.consumeKey
                }

                if (holding) {
                    holding = false
                    endDictation(event.eventTime - downAt)
                }
            }
        }
        return settings.consumeKey
    }

    /**
     * A learned key matches on whichever identifier it actually had. Scancode
     * wins when the framework could not name the key, which is the Essential
     * Key's situation.
     */
    private fun matchesTrigger(event: KeyEvent): Boolean {
        val s = prefs.now
        if (s.triggerScanCode > 0 && event.scanCode == s.triggerScanCode) return true
        return s.triggerKeyCode > 0 && event.keyCode == s.triggerKeyCode
    }

    private fun startDictation() = Dictation.begin()

    private fun endDictation(heldMs: Long) = Dictation.end(heldMs)

    // ---- putting the text back ------------------------------------------

    /**
     * Insert [text] wherever the user was typing.
     *
     * Clipboard-then-paste rather than SET_TEXT: pasting keeps the caret where it
     * was and works inside editors that manage their own text, whereas SET_TEXT
     * replaces the field wholesale and loses anything already in it.
     * Returns true if it landed in a field, false if it is only on the clipboard.
     */
    fun insertText(text: String): Boolean {
        if (text.isBlank()) return false

        val focus = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: return false

        return try {
            if (!focus.isEditable) return false

            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("Essential Voice", text))

            val pasted = focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasted) {
                releaseClipboard(clip)
                return true
            }

            // Fall back to setting text only if paste action failed
            val args = android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text,
                )
            }
            val setSuccess = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            releaseClipboard(clip)
            setSuccess
        } catch (t: Throwable) {
            Log.w(TAG, "insertText failed", t)
            false
        } finally {
            @Suppress("DEPRECATION")
            runCatching { focus.recycle() }
        }
    }
    private fun dictatedClip(text: String): ClipData =
        ClipData.newPlainText("Essential Voice", text)

    private fun releaseClipboard(clip: ClipboardManager) {
        if (Prefs.get(this).now.copyToClipboard) return
        handler.postDelayed({
            runCatching {
                clip.clearPrimaryClip()
            }.onFailure { Log.w(TAG, "could not release the clipboard", it) }
        }, 800L)
    }
}
