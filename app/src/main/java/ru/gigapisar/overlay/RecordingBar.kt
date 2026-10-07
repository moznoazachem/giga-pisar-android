package ru.gigapisar.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import ru.gigapisar.R
import ru.gigapisar.settings.AppLanguage
import kotlin.math.sin

/**
 * The strip that slides out from the floating button while it records, as in messengers:
 * a red dot, the time, and "‹ ‹ Cancel" pointing to where a slide throws the recording away.
 * Its square end touches the button, which draws the rest of the strip under itself.
 */
class RecordingBar(
    context: Context,
) : View(context) {
    companion object {
        const val HEIGHT_DP = 48
        /** Room around the strip for its shadow. */
        const val SHADOW_DP = 4
    }

    private val density = resources.displayMetrics.density

    /** -1: the strip is left of the button (its square end on the right), +1: right of it. */
    var side = -1
        set(value) {
            field = value
            invalidate()
        }

    /** Hands-free: the hint stops moving and a tap on it cancels. */
    var handsFree = false
        set(value) {
            field = value
            invalidate()
        }

    /** How far the finger went toward "cancel", 0..1. */
    var cancelProgress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var onCancel: (() -> Unit)? = null

    private var startedAt = SystemClock.uptimeMillis()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val timePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1C19.toInt()
            textSize = 15 * density
        }
    private val hintPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7B8276.toInt()
            textSize = 14 * density
        }

    private var hintRect = RectF()

    private val frame =
        object : Runnable {
            override fun run() {
                if (visibility != VISIBLE || windowToken == null) return
                invalidate()
                postOnAnimation(this)
            }
        }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun restart() {
        startedAt = SystemClock.uptimeMillis()
        handsFree = false
        cancelProgress = 0f
        removeCallbacks(frame)
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = SHADOW_DP * density
        val top = pad
        val bottom = height - pad
        val h = bottom - top
        val radius = h / 2f
        val w = width.toFloat()

        // Rounded at the far end, square where it meets the button.
        fun strip(paint: Paint, dy: Float) {
            canvas.drawRoundRect(RectF(pad, top + dy, w - pad, bottom + dy), radius, radius, paint)
            if (side < 0) {
                canvas.drawRect(w - radius - pad, top + dy, w, bottom + dy, paint)
            } else {
                canvas.drawRect(0f, top + dy, pad + radius, bottom + dy, paint)
            }
        }
        strip(shadowPaint, 1.5f * density)
        strip(barPaint, 0f)

        val cy = (top + bottom) / 2f
        val t = SystemClock.uptimeMillis() - startedAt
        val seconds = t / 1000
        val time = "%d:%02d".format(seconds / 60, seconds % 60)
        val textY = cy - (timePaint.descent() + timePaint.ascent()) / 2

        // Dot and time at the far end, the hint after them, toward the button.
        val dotR = 4.5f * density
        dotPaint.alpha = (150 + 105 * (0.5f + 0.5f * sin(t / 1000f * Math.PI.toFloat() * 2))).toInt()
        val farEdge = if (side < 0) pad + 16 * density else w - pad - 16 * density
        val dotX = if (side < 0) farEdge + dotR else farEdge - dotR
        canvas.drawCircle(dotX, cy, dotR, dotPaint)
        val timeWidth = timePaint.measureText(time)
        val timeX = if (side < 0) dotX + dotR + 8 * density else dotX - dotR - 8 * density - timeWidth
        canvas.drawText(time, timeX, textY, timePaint)

        val word = AppLanguage.wrap(context.applicationContext).getString(R.string.bar_cancel)
        val hint = if (side < 0) "‹ ‹  $word" else "$word  › ›"
        val hintWidth = hintPaint.measureText(hint)
        val gap = 14 * density
        val shift = if (handsFree) 0f else cancelProgress * 40 * density
        val hintX =
            if (side < 0) timeX + timeWidth + gap - shift else timeX - gap - hintWidth + shift
        hintPaint.alpha = (255 * (1f - cancelProgress)).toInt().coerceIn(0, 255)
        // The hint slides toward "cancel" but never over the time.
        canvas.save()
        if (side < 0) {
            canvas.clipRect(timeX + timeWidth + 6 * density, 0f, w, height.toFloat())
        } else {
            canvas.clipRect(0f, 0f, timeX - 6 * density, height.toFloat())
        }
        canvas.drawText(hint, hintX, textY, hintPaint)
        canvas.restore()
        hintRect = RectF(hintX - gap, top, hintX + hintWidth + gap, bottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!handsFree) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return hintRect.contains(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                if (hintRect.contains(event.x, event.y)) onCancel?.invoke()
                return true
            }
        }
        return true
    }
}
