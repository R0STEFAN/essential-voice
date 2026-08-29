package com.ishaan.essentialvoice

import android.content.Context
import android.content.SharedPreferences
import com.ishaan.essentialvoice.whisper.ModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An immutable read of every setting.
 *
 * The UI reads *this*, never the store. Reading SharedPreferences straight from
 * a composable looks like it works and does not: a plain getter is not a state
 * read, so nothing recomposes when the value changes and the screen only catches
 * up when the app is reopened. A snapshot published on a StateFlow is a real
 * state read, so a toggle moves the moment it is tapped.
 */
data class Settings(
    val triggerKeyCode: Int,
    val triggerScanCode: Int,
    val triggerMode: String,
    val consumeKey: Boolean,
    val holdMs: Int,
    val pillX: Float,
    val pillY: Float,
    val slideFrom: String,
    val qualityTier: String,
    val language: String,
    val idleUnloadSeconds: Int,
    val typeIntoField: Boolean,
    val copyToClipboard: Boolean,
    val haptics: Boolean,
    val updateNotices: Boolean,
    val dismissedWhatsNewFor: Int,
) {
    val hasTrigger: Boolean get() = triggerKeyCode > 0 || triggerScanCode > 0
    val tier get() = ModelCatalog.byId(qualityTier)
}

class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("essential_voice", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _state.value = read() }

    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = Settings(
        triggerKeyCode = sp.getInt(K_KEYCODE, -1),
        triggerScanCode = sp.getInt(K_SCANCODE, -1),
        triggerMode = sp.getString(K_TRIGGER_MODE, MODE_HOLD) ?: MODE_HOLD,
        consumeKey = sp.getBoolean(K_CONSUME, true),
        holdMs = sp.getInt(K_HOLD_MS, 220),
        pillX = sp.getFloat(K_PILL_X, 0.12f),
        pillY = sp.getFloat(K_PILL_Y, 0.55f),
        slideFrom = sp.getString(K_SLIDE_FROM, "auto") ?: "auto",
        qualityTier = sp.getString(K_TIER, ModelCatalog.DEFAULT_TIER_ID)
            ?: ModelCatalog.DEFAULT_TIER_ID,
        language = sp.getString(K_LANGUAGE, LANG_AUTO) ?: LANG_AUTO,
        idleUnloadSeconds = sp.getInt(K_IDLE_UNLOAD, 300),
        typeIntoField = sp.getBoolean(K_TYPE, true),
        copyToClipboard = sp.getBoolean(K_CLIPBOARD, false),
        haptics = sp.getBoolean(K_HAPTICS, true),
        updateNotices = sp.getBoolean(K_UPDATE_NOTICES, true),
        dismissedWhatsNewFor = sp.getInt(K_WHATSNEW_DISMISSED, 0),
    )

    /** The current values, for the services, which are not composing anything. */
    val now: Settings get() = _state.value

    // ---- writes ------------------------------------------------------------

    fun setTrigger(keyCode: Int, scanCode: Int) =
        sp.edit().putInt(K_KEYCODE, keyCode).putInt(K_SCANCODE, scanCode).apply()

    fun setTriggerMode(v: String) = sp.edit().putString(K_TRIGGER_MODE, v).apply()
    fun setConsumeKey(v: Boolean) = sp.edit().putBoolean(K_CONSUME, v).apply()
    fun setHoldMs(v: Int) = sp.edit().putInt(K_HOLD_MS, v).apply()
    fun setPlacement(x: Float, y: Float) =
        sp.edit().putFloat(K_PILL_X, x).putFloat(K_PILL_Y, y).apply()
    fun setSlideFrom(v: String) = sp.edit().putString(K_SLIDE_FROM, v).apply()
    fun setQualityTier(v: String) = sp.edit().putString(K_TIER, v).apply()
    fun setLanguage(v: String) = sp.edit().putString(K_LANGUAGE, v).apply()
    fun setIdleUnloadSeconds(v: Int) = sp.edit().putInt(K_IDLE_UNLOAD, v).apply()
    fun setTypeIntoField(v: Boolean) = sp.edit().putBoolean(K_TYPE, v).apply()
    fun setCopyToClipboard(v: Boolean) = sp.edit().putBoolean(K_CLIPBOARD, v).apply()
    fun setHaptics(v: Boolean) = sp.edit().putBoolean(K_HAPTICS, v).apply()
    fun setUpdateNotices(v: Boolean) = sp.edit().putBoolean(K_UPDATE_NOTICES, v).apply()

    /** When the background check last ran, and the version it last mentioned. */
    var lastUpdateCheckAt: Long
        get() = sp.getLong(K_LAST_CHECK, 0L)
        set(v) = sp.edit().putLong(K_LAST_CHECK, v).apply()

    var notifiedVersionCode: Int
        get() = sp.getInt(K_NOTIFIED, 0)
        set(v) = sp.edit().putInt(K_NOTIFIED, v).apply()

    /**
     * The build whose What's new panel has been closed.
     *
     * Held as a version rather than a flag so that closing it means "I have
     * read this one", not "never show me this again" — the next release brings
     * the panel back on its own.
     *
     * A StateFlow read like the rest of Settings, because the panel has to
     * disappear the moment the cross is pressed. See the note on [Settings].
     */
    var dismissedWhatsNewFor: Int
        get() = sp.getInt(K_WHATSNEW_DISMISSED, 0)
        set(v) = sp.edit().putInt(K_WHATSNEW_DISMISSED, v).apply()

    // ---- learn mode --------------------------------------------------------
    //
    // Not part of Settings: it is a transient conversation between the learn
    // screen and the accessibility service, not something the user configures.

    var learnMode: Boolean
        get() = sp.getBoolean(K_LEARN, false)
        set(v) = sp.edit().putBoolean(K_LEARN, v).apply()

    private val _seen = MutableStateFlow(-1 to -1)

    /** (keyCode, scanCode) of the last key seen while learning. */
    val seenKey: StateFlow<Pair<Int, Int>> = _seen

    fun reportKey(keyCode: Int, scanCode: Int) { _seen.value = keyCode to scanCode }

    fun clearSeenKey() { _seen.value = -1 to -1 }

    companion object {
        const val MODE_HOLD = "hold"
        const val MODE_TAP = "tap"

        const val LANG_AUTO = "auto"
        const val LANG_UK = "uk"
        const val LANG_EN = "en"

        private const val K_LANGUAGE = "dictation_language"
        private const val K_KEYCODE = "trigger_keycode"
        private const val K_SCANCODE = "trigger_scancode"
        private const val K_TRIGGER_MODE = "trigger_mode"
        private const val K_CONSUME = "consume_key"
        private const val K_HOLD_MS = "hold_ms"
        private const val K_PILL_X = "pill_x"
        private const val K_PILL_Y = "pill_y"
        private const val K_SLIDE_FROM = "slide_from"
        private const val K_TIER = "quality_tier"
        private const val K_IDLE_UNLOAD = "idle_unload_s"
        private const val K_TYPE = "type_into_field"
        private const val K_CLIPBOARD = "copy_to_clipboard"
        private const val K_HAPTICS = "haptics"
        private const val K_UPDATE_NOTICES = "update_notices"
        private const val K_LAST_CHECK = "last_update_check"
        private const val K_NOTIFIED = "notified_version"
        private const val K_WHATSNEW_DISMISSED = "whatsnew_dismissed"
        private const val K_LEARN = "learn_mode"

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
