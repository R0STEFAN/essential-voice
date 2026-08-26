package com.ishaan.essentialvoice

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.ishaan.essentialvoice.ui.EV
import com.ishaan.essentialvoice.ui.EssentialVoiceTheme
import com.ishaan.essentialvoice.ui.HomeScreen
import com.ishaan.essentialvoice.ui.LearnKeyScreen
import com.ishaan.essentialvoice.whisper.ModelDownloader
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    /** Permissions live outside the settings store, so they get their own state. */
    private var setupState by mutableStateOf<SetupState?>(null)

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A white app wants dark status bar icons.
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true

        prefs = Prefs.get(this)
        prefs.learnMode = false
        refresh()

        setContent {
            EssentialVoiceTheme {
                val context = LocalContext.current
                val settings by prefs.state.collectAsState()
                val download by ModelDownloader.state.collectAsState()
                val update by Updater.state.collectAsState()
                val scope = rememberCoroutineScope()
                var learning by remember { mutableStateOf(false) }
                val setup = setupState ?: Setup.read(context)

                // What's new needs the manifest to have been read, and nobody
                // opens an app in order to press a button called "check". Only
                // once, and only if nothing has looked yet this run.
                LaunchedEffect(Unit) {
                    if (Updater.state.value is Updater.State.Idle) Updater.check(context)
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(EV.Background)
                        .windowInsetsPadding(WindowInsets.systemBars),
                ) {
                    if (learning) {
                        LearnKeyScreen(
                            prefs = prefs,
                            accessibilityOn = setup.accessibility,
                            onDone = {
                                prefs.learnMode = false
                                learning = false
                                refresh()
                            },
                        )
                    } else {
                        HomeScreen(
                            setup = setup,
                            settings = settings,
                            prefs = prefs,
                            download = download,
                            update = update,
                            onRequestMic = {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onRequestNotifications = {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            onCheckUpdate = { scope.launch { Updater.check(context) } },
                            onGetUpdate = { release -> Updater.openRelease(context, release) },
                            onLearnKey = {
                                prefs.clearSeenKey()
                                prefs.learnMode = true
                                learning = true
                            },
                            onDownload = { tier ->
                                scope.launch {
                                    ModelDownloader.download(context, tier)
                                    refresh()
                                }
                            },
                            onDeleteModel = { tier ->
                                ModelDownloader.delete(context, tier)
                                refresh()
                            },
                            onCancelDownload = { ModelDownloader.cancel() },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        // Learn mode swallows keys; never leave it armed behind the user's back.
        prefs.learnMode = false
        super.onPause()
    }

    private fun refresh() {
        setupState = Setup.read(this)
    }
}
