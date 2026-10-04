package ru.gigapisar.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import ru.gigapisar.R
import kotlin.math.abs
import kotlin.math.hypot

class RecordingButton(
    context: Context,
) : View(context) {
    enum class State {
        IDLE,
        RECORDING,
        PROCESSING,
    }

    companion object {
        // The window is larger than the circle so the pulse rings fit around it.
        private const val BUTTON_SIZE_DP = 120
        private const val CIRCLE_RADIUS_DP = 43
        private const val RING_PERIOD_MS = 1600L
        private const val DRAG_WINDOW_MS = 250L
        private const val VISIBILITY_ANIMATION_DURATION = 180L
        private const val PRESSED_SCALE = 0.94f
    }

    private val density = resources.displayMetrics.density

    private val buttonSize =
        (BUTTON_SIZE_DP * density).toInt()

    private val touchSlop =
        ViewConfiguration.get(context).scaledTouchSlop

    private var state = State.IDLE

/**
     * True while the current touch gesture is being used
     * to move the floating button.
     */
    private var dragging = false

/**
     * Previous raw pointer position.
     *
     * We use deltas instead of calculating the complete position
     * here because the owner of the overlay should decide where
     * the WindowManager.LayoutParams should be placed.
     */
    private var lastRawX = 0f
    private var lastRawY = 0f

    private var downRawX = 0f
    private var downRawY = 0f

/**
     * Whether recording was started for the current gesture.
     */
    private var recordingForCurrentGesture = false

    var onRecordingStart: (() -> Unit)? = null

    var onRecordingStop: (() -> Unit)? = null

/**
     * Called once when the finger moves far enough
     * to turn the gesture into a drag.
     */
    var onDragStart: (() -> Unit)? = null

/**
     * Receives movement delta in screen coordinates.
     *
     * dx/dy are relative to the previous MotionEvent,
     * not relative to ACTION_DOWN.
     */
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null

    var onDragEnd: (() -> Unit)? = null

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val iconPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

    private val circleRadius = CIRCLE_RADIUS_DP * density

    /** Size relative to the original; everything is drawn at the original size and scaled. */
    var sizeScale = 1f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /** Microphone level 0..1 while recording; drives the glow and the halos. */
    var level: () -> Float = { 0f }

    /** Dimmed while the app is not ready (no model yet). */
    var dimmed = false
        set(value) {
            field = value
            invalidate()
        }

    private val shadowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }

    private val effectPaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val arcPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
            color = 0xFF1FA03A.toInt()
        }

    private var greenShader: Shader? = null
    private var smoothLevel = 0f
    private var animationStart = 0L

    private val frame =
        object : Runnable {
            override fun run() {
                if (state == State.IDLE || visibility != VISIBLE) return
                invalidate()
                postOnAnimation(this)
            }
        }

    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
        }

    init {
        visibility = GONE
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f

        isClickable = true
        isFocusable = true

        importantForAccessibility =
            IMPORTANT_FOR_ACCESSIBILITY_YES

        contentDescription =
            context.getString(R.string.button_idle)

        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val size = (buttonSize * sizeScale).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state != State.IDLE) {
                    return false
                }

                if (hypot(event.x - width / 2f, event.y - height / 2f) > (circleRadius + 6 * density) * sizeScale) {
                    return false
                }

                dragging = false
                recordingForCurrentGesture = true

                downRawX = event.rawX
                downRawY = event.rawY

                lastRawX = event.rawX
                lastRawY = event.rawY

            /*
             * Start recording immediately.
             *
             * Previously this was delayed by
             * ViewConfiguration.getLongPressTimeout(),
             * which made the button feel unresponsive.
             */
                setState(State.RECORDING)
                onRecordingStart?.invoke()

            /*
             * Small visual feedback that the press was accepted.
             */
                animate()
                    .scaleX(PRESSED_SCALE)
                    .scaleY(PRESSED_SCALE)
                    .setDuration(80L)
                    .start()

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (state != State.RECORDING && !dragging) {
                    return true
                }

                val totalDx =
                    event.rawX - downRawX

                val totalDy =
                    event.rawY - downRawY

            /*
             * Use Android's standard touch slop.
             *
             * This avoids interpreting tiny finger movements
             * as a drag.
             */
                if (!dragging) {
                    // Once recording is under way the finger may wander: only a quick
                    // move right after the touch turns the gesture into a drag.
                    if (event.eventTime - event.downTime > DRAG_WINDOW_MS) {
                        return true
                    }

                    val distanceExceeded =
                        abs(totalDx) > touchSlop ||
                            abs(totalDy) > touchSlop

                    if (!distanceExceeded) {
                        return true
                    }

                /*
                 * The user actually wants to move the button.
                 *
                 * Stop recording immediately and switch
                 * the current gesture to dragging.
                 */
                    dragging = true

                    if (recordingForCurrentGesture) {
                        recordingForCurrentGesture = false

                        setState(State.IDLE)
                        onRecordingStop?.invoke()
                    }

                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(80L)
                        .start()

                    onDragStart?.invoke()

                /*
                 * Don't send the whole distance from ACTION_DOWN.
                 * The owner receives movement starting from this point.
                 */
                    lastRawX = event.rawX
                    lastRawY = event.rawY

                    return true
                }

            /*
             * Smooth drag:
             *
             * Instead of calculating the complete position and
             * maintaining another coordinate system here, only
             * report the actual movement since the previous event.
             */
                val dx =
                    event.rawX - lastRawX

                val dy =
                    event.rawY - lastRawY

                if (dx != 0f || dy != 0f) {
                    onDrag?.invoke(dx, dy)
                }

                lastRawX = event.rawX
                lastRawY = event.rawY

                return true
            }

            MotionEvent.ACTION_UP -> {
                finishGesture(cancelled = false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                finishGesture(cancelled = true)
                return true
            }
        }

        return true
    }

    private fun finishGesture(cancelled: Boolean) {
        val wasDragging = dragging
        val wasRecording =
            recordingForCurrentGesture

        dragging = false
        recordingForCurrentGesture = false

        animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(80L)
            .start()

        when {
            wasDragging -> {
                onDragEnd?.invoke()
            }

            wasRecording -> {
                setState(State.PROCESSING)
                onRecordingStop?.invoke()
            }

            cancelled -> {
            /*
             * Nothing else to do.
             *
             * The recording callback has already been sent
             * if recording was active.
             */
            }
        }
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        val c = w / 2f
        val r = circleRadius
        // The website's palette: light green to teal.
        greenShader =
            LinearGradient(
                c - r,
                c - r,
                c + r,
                c + r,
                intArrayOf(0xFFA8E063.toInt(), 0xFF1FA03A.toInt(), 0xFF008F92.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val c = width / 2f
        val r = circleRadius
        val alpha = if (dimmed) 115 else 255
        canvas.save()
        canvas.scale(sizeScale, sizeScale, c, c)
        val t = (SystemClock.uptimeMillis() - animationStart).toFloat()

        if (state == State.RECORDING) {
            // Glow that follows the voice, on the same dB scale as the pill.
            val raw = level()
            val loud = raw.coerceIn(0f, 1f)
            smoothLevel += (loud - smoothLevel) * 0.3f
            effectPaint.style = Paint.Style.FILL
            effectPaint.color = 0x4063CF62
            canvas.drawCircle(c, c, r * (1.04f + 0.3f * smoothLevel), effectPaint)

            // Two halos spreading out from the button, brighter while speaking.
            effectPaint.style = Paint.Style.STROKE
            effectPaint.strokeWidth = 4f * density
            for (k in 0..1) {
                val p = ((t / RING_PERIOD_MS) + k * 0.5f) % 1f
                val strength = 0.2f + 0.6f * smoothLevel
                effectPaint.color = ((strength * (1f - p) * 255).toInt() shl 24) or 0x1FA03A
                canvas.drawCircle(c, c, r * (1f + 0.34f * p), effectPaint)
            }
        }

        canvas.drawCircle(c, c + 2 * density, r, shadowPaint.apply { this.alpha = alpha / 5 })
        paint.shader = greenShader
        paint.alpha = alpha
        canvas.drawCircle(c, c, r, paint)

        when (state) {
            State.RECORDING -> drawMicrophone(canvas = canvas, cx = c, cy = c)
            State.PROCESSING -> {
                val sweepStart = (t / 900f * 360f) % 360f
                val o = r + 7 * density
                canvas.drawArc(RectF(c - o, c - o, c + o, c + o), sweepStart, 110f, false, arcPaint)
                drawMicrophone(canvas = canvas, cx = c, cy = c)
            }
            State.IDLE -> {
                iconPaint.alpha = alpha
                strokePaint.alpha = alpha
                drawMicrophone(canvas = canvas, cx = c, cy = c)
            }
        }
        canvas.restore()
    }

    fun setState(value: State) {
        state = value

        contentDescription =
            context.getString(
                when (value) {
                    State.IDLE ->
                        R.string.button_idle

                    State.RECORDING ->
                        R.string.button_recording

                    State.PROCESSING ->
                        R.string.button_processing
                },
            )

        iconPaint.alpha = 255
        strokePaint.alpha = 255
        removeCallbacks(frame)
        if (value != State.IDLE) {
            animationStart = SystemClock.uptimeMillis()
            if (value == State.RECORDING) {
                smoothLevel = 0f
            }
            postOnAnimation(frame)
        }
        invalidate()
    }

    fun reset() {
        setState(State.IDLE)

        dragging = false
        recordingForCurrentGesture = false

        animate().cancel()

        scaleX = 1f
        scaleY = 1f
    }

    fun showAnimated(onEnd: (() -> Unit)? = null) {
        if (visibility == VISIBLE) {
            onEnd?.invoke()
            return
        }

        animate().cancel()

        visibility = VISIBLE
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f

        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(VISIBILITY_ANIMATION_DURATION)
            .withEndAction {
                onEnd?.invoke()
            }.start()
    }

    fun hideAnimated(onEnd: (() -> Unit)? = null) {
        if (visibility != VISIBLE) {
            onEnd?.invoke()
            return
        }

        animate().cancel()

        animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(VISIBILITY_ANIMATION_DURATION)
            .withEndAction {
                visibility = GONE
                alpha = 0f
                scaleX = 0.9f
                scaleY = 0.9f

                onEnd?.invoke()
            }.start()
    }

    private fun drawMicrophone(
        canvas: Canvas,
        cx: Float,
        cy: Float,
    ) {
        // Drawn on a 24-unit grid centred on (12, 12), about 35 dp across.
        val u = 1.45f * density

        fun x(v: Float) = cx + (v - 12f) * u

        fun y(v: Float) = cy + (v - 12f) * u

        strokePaint.strokeWidth = 2.2f * u
        canvas.drawRoundRect(RectF(x(8.5f), y(2.5f), x(15.5f), y(14.5f)), 3.5f * u, 3.5f * u, iconPaint)
        canvas.drawArc(RectF(x(5f), y(4f), x(19f), y(18f)), 0f, 180f, false, strokePaint)
        canvas.drawLine(x(12f), y(18f), x(12f), y(21.5f), strokePaint)
        canvas.drawLine(x(8.5f), y(21.5f), x(15.5f), y(21.5f), strokePaint)
    }
}
