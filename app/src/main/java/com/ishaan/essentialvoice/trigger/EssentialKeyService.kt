package com.ishaan.essentialvoice.trigger

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
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

        private val COMMON_PLACEHOLDERS = setOf(
            "повідомлення",
            "повідомлення...",
            "введіть повідомлення",
            "введіть повідомлення...",
            "напишіть повідомлення",
            "напишіть повідомлення...",
            "написати повідомлення",
            "написати повідомлення...",
            "коментар",
            "коментар...",
            "залишити коментар",
            "залишити коментар...",
            "додати коментар",
            "додати коментар...",
            "опис",
            "підпис",
            "пошук",
            "пошук...",
            "текст",
            "введіть текст",
            "введіть текст...",
            "message",
            "message...",
            "type a message",
            "type a message...",
            "write a message",
            "write a message...",
            "send a message",
            "send a message...",
            "comment",
            "comment...",
            "add a comment",
            "add a comment...",
            "write a comment",
            "write a comment...",
            "leave a comment",
            "leave a comment...",
            "caption",
            "caption...",
            "add a caption",
            "add a caption...",
            "search",
            "search...",
            "type here",
            "type here...",
            "enter text",
            "enter text...",
            "text message",
            "text message...",
            "сообщение",
            "сообщение...",
            "введите сообщение",
            "введите сообщение...",
            "напишите сообщение",
            "напишите сообщение...",
            "комментарий",
            "комментарий...",
            "подпись",
            "поиск",
            "поиск...",
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
    private fun findActiveInputNode(): AccessibilityNodeInfo? {
        // 1. Direct input focus on the service
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .getOrNull()
            ?.let { if (it.isEditable || it.isFocused) return it }

        // 2. Active window root input focus
        runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .getOrNull()
            ?.let { if (it.isEditable || it.isFocused) return it }

        // 3. Search across all interactive windows
        runCatching {
            for (window in windows) {
                val root = window.root ?: continue
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && (focused.isEditable || focused.isFocused)) {
                    return focused
                }
            }
        }

        // 4. Recursive search in rootInActiveWindow for focused & editable node
        runCatching {
            rootInActiveWindow?.let { root ->
                findFocusedEditableRecursive(root)?.let { return it }
            }
        }

        return null
    }

    private fun findFocusedEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditableRecursive(child)
            if (found != null) return found
        }
        return null
    }

    private fun isHintOrPlaceholder(focus: AccessibilityNodeInfo): Boolean {
        if (focus.isShowingHintText) return true

        val text = focus.text?.toString()?.trim() ?: return true
        if (text.isEmpty()) return true

        val hint = focus.hintText?.toString()?.trim()
        if (hint != null && text.equals(hint, ignoreCase = true)) return true

        val desc = focus.contentDescription?.toString()?.trim()
        if (desc != null && text.equals(desc, ignoreCase = true)) return true

        val extraHint = runCatching {
            focus.extras?.getCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.HINT_TEXT_KEY")
                ?: focus.extras?.getCharSequence("AccessibilityNodeInfo.hintText")
        }.getOrNull()?.toString()?.trim()
        if (extraHint != null && text.equals(extraHint, ignoreCase = true)) return true

        val lower = text.lowercase()
        val selStart = focus.textSelectionStart
        val selEnd = focus.textSelectionEnd

        // If cursor/selection is at the start (0 or -1), check common placeholder heuristics
        if (selStart <= 0 && selEnd <= 0) {
            if (COMMON_PLACEHOLDERS.contains(lower)) return true

            // Telegram specifically sets text to getHint() when field is empty
            val pkg = focus.packageName?.toString()?.lowercase() ?: ""
            if (pkg.contains("telegram") || pkg.contains("chatalert") || pkg.contains("messenger")) {
                if (lower.length <= 40 && (
                        lower.contains("повідомлен") ||
                        lower.contains("message") ||
                        lower.contains("сообщен") ||
                        lower.contains("комент") ||
                        lower.contains("comment") ||
                        lower.contains("пошук") ||
                        lower.contains("search") ||
                        lower.contains("підпис") ||
                        lower.contains("caption")
                    )) {
                    return true
                }
            }
        }

        return false
    }

    fun insertText(text: String): Boolean {
        if (text.isBlank()) return false

        val focus = findActiveInputNode() ?: return false

        return try {
            if (!focus.isEditable) return false

            val shouldCopy = Prefs.get(this).now.copyToClipboard

            if (!shouldCopy) {
                // Direct insertion: does NOT touch the clipboard at all, avoiding system paste popups and Gboard banners
                val rawText = if (isHintOrPlaceholder(focus) || focus.text == null) "" else focus.text.toString()

                val selStart = focus.textSelectionStart
                val selEnd = focus.textSelectionEnd

                val newText = if (rawText.isEmpty()) {
                    text
                } else if (selStart in 0..rawText.length && selEnd in selStart..rawText.length) {
                    val prefix = rawText.substring(0, selStart)
                    val suffix = rawText.substring(selEnd)
                    val spaceBefore = if (prefix.isNotEmpty() && !prefix.endsWith(" ") && !prefix.endsWith("\n")) " " else ""
                    val spaceAfter = if (suffix.isNotEmpty() && !suffix.startsWith(" ") && !suffix.startsWith("\n")) " " else ""
                    "$prefix$spaceBefore$text$spaceAfter$suffix"
                } else {
                    val space = if (!rawText.endsWith(" ") && !rawText.endsWith("\n")) " " else ""
                    "$rawText$space$text"
                }

                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                val setOk = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (setOk) {
                    val targetCursor = if (rawText.isEmpty()) {
                        text.length
                    } else if (selStart in 0..rawText.length && selEnd in selStart..rawText.length) {
                        val prefix = rawText.substring(0, selStart)
                        val spaceBefore = if (prefix.isNotEmpty() && !prefix.endsWith(" ") && !prefix.endsWith("\n")) " " else ""
                        (prefix.length + spaceBefore.length + text.length).coerceIn(0, newText.length)
                    } else {
                        newText.length
                    }
                    val selArgs = android.os.Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, targetCursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, targetCursor)
                    }
                    focus.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                    return true
                }
                // Direct insertion failed and copyToClipboard is OFF — NEVER touch clipboard!
                return false
            }

            // Fallback for copyToClipboard = true:
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("Essential Voice", text))

            val pasted = focus.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasted) {
                return true
            }

            val args = android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text,
                )
            }
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            Log.w(TAG, "insertText failed", t)
            false
        } finally {
            @Suppress("DEPRECATION")
            runCatching { focus.recycle() }
        }
    }
}
