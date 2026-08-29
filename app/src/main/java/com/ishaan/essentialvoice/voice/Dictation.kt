package com.ishaan.essentialvoice.voice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.Toast
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.whisper.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One dictation, start to finish: the pill, the microphone and the transcript.
 *
 * Hosted by the accessibility service rather than a foreground service, which is
 * the whole reason this app has no permanent notification. The system binds an
 * accessibility service with BIND_FOREGROUND_SERVICE, so the process sits at a
 * uid state that is allowed to open the microphone — no foreground service, and
 * so no notification, required. If that ever stops being true the failure is
 * loud rather than silent: Android hands out digital silence instead of an
 * error, so a clip whose peak is exactly zero is reported as a blocked mic.
 */
object Dictation {

    private const val TAG = "EVDictation"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val ENTER: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val EXIT: Interpolator = PathInterpolator(0.5f, 0f, 0.9f, 0.2f)

    private var app: Context? = null
    private var wm: WindowManager? = null
    private var prefs: Prefs? = null

    private var pill: PillView? = null
    private var params: WindowManager.LayoutParams? = null
    private var slideAnim: ValueAnimator? = null
    private var attached = false

    private var recorder: Recorder? = null
    private var capturing = false
    private var busy = false
    private var work: Job? = null
    private var idleJob: Job? = null

    val isListening: Boolean get() = busy && capturing

    /** Called by the accessibility service once the system has bound it. */
    fun attach(context: Context) {
        val c = context.applicationContext
        app = c
        wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(c)
        Log.i(TAG, "ready — ${WhisperEngine.systemInfo()}")
    }

    fun detach() {
        work?.cancel()
        idleJob?.cancel()
        slideAnim?.cancel()
        recorder?.release()
        recorder = null
        capturing = false
        busy = false
        pill?.let { p -> if (attached) runCatching { wm?.removeViewImmediate(p) } }
        attached = false
        pill = null
        app = null
    }

    val isReady: Boolean get() = app != null

    // ---- the gesture -------------------------------------------------------

    fun begin() {
        val ctx = app ?: return
        val prefs = prefs ?: return
        if (busy) return

        if (!Settings.canDrawOverlays(ctx)) {
            toast("Essential Voice needs \"draw over other apps\"")
            return
        }

        val p = attachPill() ?: return
        busy = true
        p.reset(PillView.State.LISTENING)
        tick(18)

        val rec = recorder ?: Recorder { level -> pill?.pushLevel(level) }.also { recorder = it }
        if (!rec.start()) {
            finish(PillView.State.ERROR, "The microphone could not be opened")
            return
        }
        capturing = true

        // Loading costs a couple of hundred milliseconds; overlap it with the
        // sentence rather than making the user wait for it after they stop.
        work = scope.launch { withContext(Dispatchers.Default) { WhisperEngine.warm(ctx) } }
    }


    fun end(heldMs: Long = Long.MAX_VALUE) {
        val ctx = app ?: return
        val prefs = prefs ?: return
        if (!busy || !capturing) return

        val rec = recorder ?: return
        val audio = rec.stop()
        capturing = false

        val seconds = audio.size.toFloat() / SAMPLE_RATE
        val minMs = if (prefs.now.triggerMode == Prefs.MODE_TAP) 0 else 350
        if (heldMs < minMs || seconds < 0.25f) {
            finish(PillView.State.ERROR, null)
            return
        }

        // Android hands a blocked recorder digital silence rather than an error,
        // so an exactly-zero peak is the signature of a permission problem, not
        // of a quiet room.
        if (Audio.peak(audio) == 0f) {
            finish(PillView.State.ERROR, "The microphone returned silence — check its permission")
            return
        }

        pill?.morphTo(PillView.State.THINKING)
        tick(10)

        work = scope.launch {
            val started = System.currentTimeMillis()
            val prepared = withContext(Dispatchers.Default) {
                Audio.normalise(audio)
                Audio.padTo(audio, 0.6f)
            }

            WhisperEngine.transcribe(ctx, prepared).fold(
                onSuccess = { text ->
                    val ms = System.currentTimeMillis() - started
                    Log.i(TAG, "transcribed ${"%.1f".format(seconds)}s in ${ms}ms: \"$text\"")
                    when {
                        text.isBlank() -> finish(PillView.State.ERROR, null)
                        deliver(ctx, text) -> finish(PillView.State.DONE, null)
                        else -> finish(
                            PillView.State.ERROR,
                            "Nowhere to type that, so it is on the clipboard",
                        )
                    }
                },
                onFailure = { t ->
                    Log.w(TAG, "transcribe failed", t)
                    finish(PillView.State.ERROR, t.message)
                },
            )
        }
    }

    fun toggle() = if (isListening) end() else begin()

    fun cancel() {
        if (!busy) return
        WhisperEngine.abort()
        work?.cancel()
        if (capturing) { recorder?.stop(); capturing = false }
        finish(PillView.State.ERROR, null)
    }

    /**
     * Hand the text over however the user asked for it. The two destinations are
     * independent — typing it in and keeping a copy are different wants — so
     * this reports success if *either* landed.
     */
    private fun deliver(ctx: Context, text: String): Boolean {
        val s = prefs?.now ?: return false
        var landed = false

        if (s.copyToClipboard) {
            copy(ctx, text)
            landed = true
        }
        if (s.typeIntoField) {
            val typed = com.ishaan.essentialvoice.trigger.EssentialKeyService
                .instance?.insertText(text) ?: false
            if (typed) {
                landed = true
            } else if (!s.copyToClipboard) {
                toast("No text field was focused")
                landed = true
            }
        }
        if (!landed) {
            // Both switches off: say the words rather than lose them.
            toast(text)
            landed = true
        }
        return landed
    }

    private fun copy(ctx: Context, text: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("Essential Voice", text))
    }

    /**
     * Close out: show the ending glyph, say anything the user needs to act on,
     * then slide the pill back out the way it came.
     */
    private fun finish(state: PillView.State, message: String?) {
        message?.let { toast(it) }
        val p = pill
        if (p == null) { busy = false; return }
        p.morphTo(state)
        if (state == PillView.State.DONE) tick(24)

        val linger = if (state == PillView.State.DONE) 420L else 620L
        main.postDelayed({
            detachPill()
            busy = false
            scheduleIdleUnload()
        }, linger)
    }

    private fun toast(text: String) {
        val ctx = app ?: return
        main.post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
    }

    // ---- the window --------------------------------------------------------

    private fun dp(v: Float): Float {
        val d = app?.resources?.displayMetrics?.density ?: 3f
        return v * d
    }

    /**
     * A window only as big as the pill.
     *
     * Deliberately not full-screen: the system caps a touch-passthrough overlay
     * at 0.8 opacity, which would leave the pill translucent. A small window
     * that takes its own touches keeps full opacity, and it only covers anything
     * while a dictation is actually happening. FLAG_NOT_FOCUSABLE has to stay —
     * the field being typed into must keep input focus or the text has nowhere
     * to land.
     */
    private fun buildParams() = WindowManager.LayoutParams(
        dp(PillView.WINDOW_W_DP).toInt(),
        dp(PillView.WINDOW_H_DP).toInt(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun screen(): Pair<Int, Int> {
        val b = wm?.maximumWindowMetrics?.bounds ?: return 1080 to 2400
        return b.width() to b.height()
    }

    private fun slidesFromRight(): Boolean {
        val s = prefs?.now ?: return true
        return when (s.slideFrom) {
            "left" -> false
            "right" -> true
            else -> s.pillX >= 0.5f
        }
    }

    private fun attachPill(): PillView? {
        val ctx = app ?: return null
        val wm = wm ?: return null
        val s = prefs?.now ?: return null

        val p = pill ?: PillView(ctx).also { pill = it }
        val lp = params ?: buildParams().also { params = it }

        val (sw, sh) = screen()
        val targetX = (s.pillX * sw - lp.width / 2f).toInt()
            .coerceIn(0, (sw - lp.width).coerceAtLeast(0))
        lp.y = (s.pillY * sh - lp.height / 2f).toInt()
            .coerceIn(0, (sh - lp.height).coerceAtLeast(0))
        lp.x = if (slidesFromRight()) sw else -lp.width

        if (!attached) {
            runCatching { wm.addView(p, lp) }
                .onFailure { Log.e(TAG, "addView failed", it); return null }
            attached = true
        } else {
            runCatching { wm.updateViewLayout(p, lp) }
        }

        slideTo(targetX, 380, ENTER, null)
        return p
    }

    private fun detachPill() {
        val p = pill ?: return
        val wm = wm ?: return
        if (!attached) { pill = null; return }
        val lp = params ?: return
        val (sw, _) = screen()
        slideTo(if (slidesFromRight()) sw else -lp.width, 260, EXIT) {
            p.stop()
            if (attached) runCatching { wm.removeViewImmediate(p) }
            attached = false
        }
    }

    /**
     * The intro and outro are the window moving, not the view drawing itself
     * somewhere else — a view cannot paint outside its own surface.
     */
    private fun slideTo(toX: Int, ms: Long, interp: Interpolator, onEnd: (() -> Unit)?) {
        val p = pill ?: return
        val lp = params ?: return
        val wm = wm ?: return
        slideAnim?.cancel()
        slideAnim = ValueAnimator.ofInt(lp.x, toX).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                lp.x = it.animatedValue as Int
                if (attached) runCatching { wm.updateViewLayout(p, lp) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd?.invoke() }
            })
            start()
        }
    }

    // ---- odds and ends -----------------------------------------------------

    private fun tick(ms: Long) {
        if (prefs?.now?.haptics != true) return
        buzz(app ?: return, ms)
    }

    /** Also used by the settings screen, so switching haptics on can be felt. */
    fun buzz(context: Context, ms: Long) {
        val v = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, 90)) }
    }

    private fun scheduleIdleUnload() {
        val ctx = app ?: return
        idleJob?.cancel()
        val window = prefs?.now?.idleUnloadSeconds ?: return
        if (window <= 0) return
        idleJob = scope.launch {
            delay(window * 1000L + 2_000L)
            if (!busy) WhisperEngine.unloadIfIdle(ctx)
        }
    }

    /** The chosen tier changed; drop whatever is resident. */
    fun onTierChanged() {
        scope.launch { WhisperEngine.unload() }
    }
}
