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
import ru.gigapisar.settings.AppLanguage
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
        private const val LOCK_DISTANCE_DP = 80
        private const val CANCEL_DISTANCE_DP = 110
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

    /**
     * Hands-free gestures, as in messengers: while holding, a slide up to the lock keeps the
     * recording going without the finger, a slide toward the middle of the screen throws it
     * away. [onSwipe] reports how far the finger went (inward and up, in pixels, only the
     * leading direction) so the owner can move the button and the hints along.
     */
    var onSwipe: ((inward: Float, up: Float) -> Unit)? = null
    var onLock: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    /** -1 when the middle of the screen is to the left of the button, +1 when to the right. */
    var inwardSign = -1f

    /** Turquoise blue instead of the site's green, when not recording (a setting). */
    var blue = false
        set(value) {
            field = value
            arcPaint.color = if (value) 0xFF0B6FA8.toInt() else 0xFF1FA03A.toInt()
            invalidate()
        }

    /** Slides to the lock and to "cancel"; off in the settings. */
    var gestures = true

    /** -1 when the lock is above the button, +1 when it had to go below. */
    var lockSign = -1f

    /** Locked by a slide up: the next tap on the button ends the recording. */
    var handsFree = false
        set(value) {
            field = value
            contentDescription = text(if (value) R.string.button_insert else currentDescription())
            invalidate()
        }

    /**
     * Side where the recording bar sits (-1 left, +1 right, 0 none): the button draws the bar's
     * end under itself, so the bar in its own window and the circle meet without a seam.
     */
    var barSide = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Bar height in dp, shared with the bar window. */
    val barHeightDp = RecordingBar.HEIGHT_DP

    /** What the current touch turned out to be. */
    private enum class Gesture { NONE, HOLD, TAP_HANDS_FREE, DONE }

    private var gesture = Gesture.NONE

    private val lockDistance get() = LOCK_DISTANCE_DP * density * sizeScale
    private val cancelDistance get() = CANCEL_DISTANCE_DP * density * sizeScale

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
    private var redShader: Shader? = null
    private var blueShader: Shader? = null
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    private val barPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
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
                if (hypot(event.x - width / 2f, event.y - height / 2f) > (circleRadius + 6 * density) * sizeScale) {
                    return false
                }

                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false

                if (state == State.RECORDING && handsFree) {
                    // The tap that ends a hands-free recording (or a slide that cancels it).
                    gesture = Gesture.TAP_HANDS_FREE
                    pressIn()
                    return true
                }

                if (state != State.IDLE) {
                    return false
                }

                gesture = Gesture.HOLD
                recordingForCurrentGesture = true

                // Recording starts right away: a delay made the button feel unresponsive.
                setState(State.RECORDING)
                onRecordingStart?.invoke()
                pressIn()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY

                when (gesture) {
                    Gesture.HOLD -> {
                        if (dragging) {
                            dragBy(event)
                            return true
                        }
                        if (state != State.RECORDING) return true

                        // Only a quick move right after the touch moves the button; later the
                        // finger is sliding to the lock or toward "cancel".
                        if (event.eventTime - event.downTime <= DRAG_WINDOW_MS) {
                            if (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop) {
                                startDrag(event)
                            }
                            return true
                        }

                        if (!gestures) return true

                        val inward = (totalDx * inwardSign).coerceAtLeast(0f)
                        val up = (totalDy * lockSign).coerceAtLeast(0f)
                        when {
                            up >= lockDistance && up >= inward -> {
                                gesture = Gesture.DONE
                                onSwipe?.invoke(0f, 0f)
                                pressOut()
                                handsFree = true
                                onLock?.invoke()
                            }
                            inward >= cancelDistance && inward > up -> {
                                gesture = Gesture.DONE
                                recordingForCurrentGesture = false
                                onSwipe?.invoke(0f, 0f)
                                pressOut()
                                onCancel?.invoke()
                            }
                            else -> onSwipe?.invoke(if (inward > up) inward else 0f, if (up >= inward) up else 0f)
                        }
                        return true
                    }

                    Gesture.TAP_HANDS_FREE -> {
                        val inward = (totalDx * inwardSign).coerceAtLeast(0f)
                        if (inward >= cancelDistance) {
                            gesture = Gesture.DONE
                            onSwipe?.invoke(0f, 0f)
                            pressOut()
                            onCancel?.invoke()
                        } else {
                            onSwipe?.invoke(inward, 0f)
                        }
                        return true
                    }

                    else -> return true
                }
            }

            MotionEvent.ACTION_UP -> {
                finishGesture(cancelled = false, event = event)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                finishGesture(cancelled = true, event = event)
                return true
            }
        }

        return true
    }

    private fun startDrag(event: MotionEvent) {
        // The user wants to move the button: drop the recording that the touch started.
        dragging = true
        if (recordingForCurrentGesture) {
            recordingForCurrentGesture = false
            setState(State.IDLE)
            onRecordingStop?.invoke()
        }
        pressOut()
        onDragStart?.invoke()
        // The owner gets movement from this point on, not the whole distance from the touch.
        lastRawX = event.rawX
        lastRawY = event.rawY
    }

    private fun dragBy(event: MotionEvent) {
        // Deltas since the previous event; the owner decides where the window goes.
        val dx = event.rawX - lastRawX
        val dy = event.rawY - lastRawY
        if (dx != 0f || dy != 0f) {
            onDrag?.invoke(dx, dy)
        }
        lastRawX = event.rawX
        lastRawY = event.rawY
    }

    private fun pressIn() {
        animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE).setDuration(80L).start()
    }

    private fun pressOut() {
        animate().scaleX(1f).scaleY(1f).setDuration(80L).start()
    }

    private fun finishGesture(
        cancelled: Boolean,
        event: MotionEvent,
    ) {
        val wasGesture = gesture
        val wasDragging = dragging
        val wasRecording = recordingForCurrentGesture
        gesture = Gesture.NONE
        dragging = false
        recordingForCurrentGesture = false
        pressOut()

        when {
            wasDragging -> onDragEnd?.invoke()

            wasGesture == Gesture.HOLD && wasRecording -> {
                onSwipe?.invoke(0f, 0f)
                // The recorder may have stopped by itself (5-minute limit) under the finger.
                if (state == State.RECORDING) {
                    setState(State.PROCESSING)
                    onRecordingStop?.invoke()
                }
            }

            wasGesture == Gesture.TAP_HANDS_FREE -> {
                onSwipe?.invoke(0f, 0f)
                val moved = abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop
                if (!cancelled && !moved && state == State.RECORDING) {
                    handsFree = false
                    setState(State.PROCESSING)
                    onRecordingStop?.invoke()
                }
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
        blueShader =
            LinearGradient(
                c - r,
                c - r,
                c + r,
                c + r,
                intArrayOf(0xFF7FE3F0.toInt(), 0xFF1FA3D6.toInt(), 0xFF0B6FA8.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        redShader =
            LinearGradient(
                c - r,
                c - r,
                c + r,
                c + r,
                intArrayOf(0xFFFF8A80.toInt(), 0xFFE53935.toInt(), 0xFFB71C1C.toInt()),
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

        val red = state == State.RECORDING

        if (barSide != 0 && state == State.RECORDING) {
            // The end of the recording bar, under the circle (see [barSide]).
            val h = barHeightDp * density / 2f
            val edge = if (barSide < 0) c - width / (2f * sizeScale) else c + width / (2f * sizeScale)
            canvas.drawRect(minOf(edge, c), c - h, maxOf(edge, c), c + h, barPaint)
        }

        if (state == State.RECORDING) {
            // Glow that follows the voice, on the same dB scale as the pill.
            val raw = level()
            val loud = raw.coerceIn(0f, 1f)
            smoothLevel += (loud - smoothLevel) * 0.3f
            effectPaint.style = Paint.Style.FILL
            effectPaint.color = if (red) 0x40EF5350 else 0x4063CF62
            canvas.drawCircle(c, c, r * (1.04f + 0.3f * smoothLevel), effectPaint)

            // Two halos spreading out from the button, brighter while speaking.
            effectPaint.style = Paint.Style.STROKE
            effectPaint.strokeWidth = 4f * density
            for (k in 0..1) {
                val p = ((t / RING_PERIOD_MS) + k * 0.5f) % 1f
                val strength = 0.2f + 0.6f * smoothLevel
                effectPaint.color = ((strength * (1f - p) * 255).toInt() shl 24) or (if (red) 0xE53935 else 0x1FA03A)
                canvas.drawCircle(c, c, r * (1f + 0.34f * p), effectPaint)
            }
        }

        canvas.drawCircle(c, c + 2 * density, r, shadowPaint.apply { this.alpha = alpha / 5 })
        paint.shader = if (red) redShader else if (blue) blueShader else greenShader
        paint.alpha = alpha
        canvas.drawCircle(c, c, r, paint)

        when (state) {
            State.RECORDING ->
                if (handsFree) {
                    // Fits the word inside the circle: "Вставить" and "Insert" alike.
                    val word = text(R.string.button_insert)
                    labelPaint.textSize = 16 * density
                    val maxWidth = r * 1.6f
                    val w = labelPaint.measureText(word)
                    if (w > maxWidth) labelPaint.textSize *= maxWidth / w
                    canvas.drawText(word, c, c - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint)
                } else {
                    drawMicrophone(canvas = canvas, cx = c, cy = c)
                }
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

    /** In the language chosen in the app, not only the phone's. */
    private fun text(id: Int) = AppLanguage.wrap(context.applicationContext).getString(id)

    private fun currentDescription() =
        when (state) {
            State.IDLE -> R.string.button_idle
            State.RECORDING -> R.string.button_recording
            State.PROCESSING -> R.string.button_processing
        }

    fun setState(value: State) {
        state = value
        if (value != State.RECORDING) {
            handsFree = false
            barSide = 0
        }

        contentDescription = text(currentDescription())

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
        gesture = Gesture.NONE

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
