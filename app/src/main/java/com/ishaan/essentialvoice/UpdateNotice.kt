package com.ishaan.essentialvoice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Tells you once, quietly, when a newer build exists.
 *
 * There is no store to do this, and the alternative is remembering to open the
 * app and press a button — which nobody does. It runs off the accessibility
 * service rather than a scheduler: that service is already long-lived, so a
 * once-a-day check costs nothing and adds no dependency.
 *
 * One notification per version, ever. A build you have already been told about
 * is not mentioned again, however many times the check runs.
 */
object UpdateNotice {

    private const val TAG = "EVUpdate"
    private const val CHANNEL = "essential_voice_updates"
    private const val NOTIF_ID = 77
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    suspend fun checkIfDue(context: Context) {
        val prefs = Prefs.get(context)
        if (!prefs.now.updateNotices) return

        val since = System.currentTimeMillis() - prefs.lastUpdateCheckAt
        if (since < CHECK_INTERVAL_MS) return
        prefs.lastUpdateCheckAt = System.currentTimeMillis()

        when (val state = Updater.check(context)) {
            is Updater.State.Available -> notify(context, state.release)
            else -> Log.i(TAG, "no update (${state.javaClass.simpleName})")
        }
        // The panel in the app should not open showing the result of a check the
        // user never asked for.
        Updater.reset()
    }

    private fun notify(context: Context, release: Updater.Release) {
        val prefs = Prefs.get(context)
        if (prefs.notifiedVersionCode >= release.versionCode) return

        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "v${release.versionName} is out, but notifications are not granted")
            return
        }

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "New versions", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Shown once when a newer build is published" },
            )
        }

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse(release.page))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        nm.notify(
            NOTIF_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Essential Voice ${release.versionName} is out")
                .setContentText(release.notes.ifBlank { "Tap to see what changed." })
                .setStyle(Notification.BigTextStyle().bigText(release.notes))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        prefs.notifiedVersionCode = release.versionCode
        Log.i(TAG, "notified about v${release.versionName}")
    }
}
