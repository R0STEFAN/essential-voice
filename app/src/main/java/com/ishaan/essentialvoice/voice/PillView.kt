package com.ishaan.essentialvoice.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * The pill: a small flat-yellow lozenge holding five black dots.
 *
 * Modelled directly on the reference — squat, barely wider than the dots it
 * carries, and fully opaque. There is no text and no transparency anywhere in
 * it: the dots carry every state, the way the Glyph does on the back of the
 * phone. Sliding in from the edge is done by moving the *window*, which is why
 * this view knows nothing about where on screen it sits.
 *
 * Drawn rather than composed of child views because it repaints on every audio
 * buffer and has to stay cheap on top of whatever app is in focus.
 */
class PillView(context: Context) : View(context) {

    enum class State { HIDDEN, LISTENING, THINKING, DONE, ERROR }

    companion object {
        const val YELLOW = 0xFFFFD900.toInt()
        const val INK = 0xFF1B1B1D.toInt()

        /** Window the pill is drawn inside, in dp. A little margin either side. */
        const val WINDOW_W_DP = 96f
        const val WINDOW_H_DP = 76f

        const val PILL_W_DP = 76f
        private const val PILL_H_DP = 54f

        /** Five, as in the reference. */
        const val DOTS = 5

        // Tighter together than the pill is wide, so the dots sit in a clear
        // island of yellow rather than running to the edges.
        private const val BAR_STEP_DP = 7.6f
        private const val BAR_W_DP = 4.0f
        private const val BAR_MIN_H_DP = 4.0f
        private const val BAR_MAX_H_DP = 30f
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
        textSize = dp(13f)
        typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(
                context, com.ishaan.essentialvoice.R.font.geist_mono_medium,
            )
        }.getOrNull() ?: Typeface.MONOSPACE
    }


    private val rect = RectF()
    private val bar = RectF()

    /** Rolling amplitude history, newest at the right. */
    private val levels = FloatArray(DOTS)

    private var phase = 0f

    var state: State = State.HIDDEN
        private set

    private var phaseAnim: ValueAnimator? = null

    // ---- transitions -------------------------------------------------------

    fun reset(next: State) {
        state = next
        levels.fill(0f)
        startPhase()
        invalidate()
    }

    /**
     * The pill never changes size, so a state change is only a change of glyph.
     * [message] is accepted and ignored: the reason for a failure belongs in the
     * app and the notification, not on a 74dp lozenge.
     */
    fun morphTo(next: State, @Suppress("UNUSED_PARAMETER") message: String = "") {
        if (state == next) return
        state = next
        invalidate()
    }

    fun stop() {
        state = State.HIDDEN
        stopPhase()
    }

    /** Called from the mic thread on every buffer. */
    fun pushLevel(peak: Float) {
        // Speech peaks well below full scale; map the useful range onto 0..1
        // rather than drawing five permanently identical dots.
        val shaped = min(1f, peak / 0.35f)
        post {
            System.arraycopy(levels, 1, levels, 0, DOTS - 1)
            levels[DOTS - 1] = shaped
            invalidate()
        }
    }

    private fun startPhase() {
        if (phaseAnim?.isRunning == true) return
        phaseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopPhase() {
        phaseAnim?.cancel()
        phaseAnim = null
    }

    override fun onDetachedFromWindow() {
        stopPhase()
        super.onDetachedFromWindow()
    }

    // ---- drawing -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (state == State.HIDDEN) return

        val cx = width / 2f
        val cy = height / 2f
        val hw = dp(PILL_W_DP) / 2f
        val hh = dp(PILL_H_DP) / 2f
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        // Full radius: the corner is half the height, so the ends are true
        // semicircles and the shape is a stadium rather than a rounded box.
        val r = hh
        canvas.drawRoundRect(rect, r, r, pillPaint)

        when (state) {
            State.LISTENING -> drawLevels(canvas, cx, cy)
            State.THINKING -> drawThinking(canvas, cx, cy)
            State.DONE -> drawDone(canvas, cy)
            State.ERROR -> drawCross(canvas, cx, cy)
            State.HIDDEN -> Unit
        }
    }

    private fun dotX(i: Int, cx: Float) = cx + (i - (DOTS - 1) / 2f) * dp(BAR_STEP_DP)

    /**
     * One bar per column, grown from the centre. At rest each is as tall as it
     * is wide, so the row reads as the five dots in the reference; loudness
     * stretches them into a waveform rather than fattening them into blobs.
     */
    private fun drawBar(canvas: Canvas, x: Float, cy: Float, level: Float) {
        val w = dp(BAR_W_DP)
        val h = dp(BAR_MIN_H_DP) + (dp(BAR_MAX_H_DP) - dp(BAR_MIN_H_DP)) * level
        bar.set(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(bar, w / 2f, w / 2f, dotPaint)
    }

    private fun drawLevels(canvas: Canvas, cx: Float, cy: Float) {
        for (i in 0 until DOTS) drawBar(canvas, dotX(i, cx), cy, levels[i])
    }

    /** One bar stretching and settling along the row while whisper decodes. */
    private fun drawThinking(canvas: Canvas, cx: Float, cy: Float) {
        val head = phase * DOTS
        for (i in 0 until DOTS) {
            val d = min(abs(head - i), abs(head - i - DOTS))
            drawBar(canvas, dotX(i, cx), cy, (1f - min(1f, d)).coerceAtLeast(0f) * 0.75f)
        }
    }

    /** Says so, rather than making you read a tick made of dots. */
    private fun drawDone(canvas: Canvas, cy: Float) {
        val fm = textPaint.fontMetrics
        canvas.drawText(
            "DONE", width / 2f, cy - (fm.ascent + fm.descent) / 2f, textPaint,
        )
    }

    /** A cross on the same five columns: something went wrong, look in the app. */
    private fun drawCross(canvas: Canvas, cx: Float, cy: Float) {
        val r = dp(2.2f)
        val s = dp(5.0f)
        canvas.drawCircle(dotX(0, cx), cy - s, r, dotPaint)
        canvas.drawCircle(dotX(4, cx), cy - s, r, dotPaint)
        canvas.drawCircle(dotX(2, cx), cy, r, dotPaint)
        canvas.drawCircle(dotX(0, cx), cy + s, r, dotPaint)
        canvas.drawCircle(dotX(4, cx), cy + s, r, dotPaint)
    }
}
