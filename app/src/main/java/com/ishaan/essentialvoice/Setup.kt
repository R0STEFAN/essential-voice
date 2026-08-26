package com.ishaan.essentialvoice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ishaan.essentialvoice.trigger.EssentialKeyService
import com.ishaan.essentialvoice.voice.Dictation

/** Everything the app needs granted before a held key can become text. */
data class SetupState(
    val accessibility: Boolean,
    val overlay: Boolean,
    val microphone: Boolean,
    val keyLearned: Boolean,
) {
    /** Enough to run a dictation, whatever the trigger. */
    val canDictate: Boolean get() = accessibility && overlay && microphone

    val ready: Boolean get() = canDictate && keyLearned
}

object Setup {

    fun read(context: Context): SetupState = SetupState(
        accessibility = isAccessibilityEnabled(context),
        overlay = Settings.canDrawOverlays(context),
        microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
        keyLearned = Prefs.get(context).now.hasTrigger,
    )

    /**
     * Read from Settings.Secure rather than trusting the service's own static
     * instance: the instance is only set once the system has actually bound us,
     * and the settings screen needs to reflect the switch, not the binding race.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, EssentialKeyService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * The assistant picker. Android has no direct action for it, so this opens
     * the default-apps screen the picker lives on, falling back to the app's own
     * settings page if the OEM has moved it.
     */
    fun openAssistantSettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return }
            }
        }
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
