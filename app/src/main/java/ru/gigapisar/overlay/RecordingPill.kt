package ru.gigapisar.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The recording pill, as on the desktop apps: a small capsule with a wave that follows
 * the voice while recording, a calm ripple while recognizing, and a short message on
 * errors. It sits just above the text field the text will go into (or at the bottom
 * of the screen when there is none), never takes focus and ignores touches, so it
 * works the same with the floating button hidden and the volume key doing the work.
 */
class RecordingPill(
    private val service: AccessibilityService,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val view = PillView(service)
    private var attached = false
    private val hideRunnable = Runnable { hide() }

    private val params =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Recording started: the wave follows [level] (0..1), placed near [anchor] (screen coordinates). */
    fun showListening(
        anchor: Rect?,
        level: () -> Float,
    ) {
        view.removeCallbacks(hideRunnable)
        dropAction()
        keepScreenOn = true
        view.listen(level)
        place(anchor)
    }

    /** Recording is over, recognition runs. */
    fun showProcessing() {
        view.removeCallbacks(hideRunnable)
        dropAction()
        keepScreenOn = true
        view.process()
        place(lastAnchor)
    }

    /** A short message (an error or a hint) that goes away by itself. */
    fun showMessage(
        text: String,
        anchor: Rect? = lastAnchor,
        millis: Long = 2600,
    ) {
        view.removeCallbacks(hideRunnable)
        dropAction()
        keepScreenOn = false
        view.message(text)
        place(anchor)
        view.postDelayed(hideRunnable, millis)
    }

    /**
     * A message with a tappable action ("Мозг поправил · Вернуть"). Only while it shows, the
     * pill takes touches, and only on itself; the rest of the screen works as usual.
     */
    fun showAction(
        text: String,
        action: String,
        anchor: Rect?,
        millis: Long,
        onAction: () -> Unit,
    ) {
        view.removeCallbacks(hideRunnable)
        keepScreenOn = false
        view.message(text, action)
        view.setOnClickListener {
            hide()
            onAction()
        }
        touchable = true
        place(anchor)
        view.postDelayed(hideRunnable, millis)
    }

    /** True while an action is offered; the service hides it once the user types or leaves. */
    val showsAction: Boolean
        get() = attached && touchable

    fun hide() {
        view.removeCallbacks(hideRunnable)
        view.stop()
        dropAction()
        if (!attached) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Already gone.
        }
        attached = false
    }

    private fun dropAction() {
        view.setOnClickListener(null)
        view.isClickable = false
        touchable = false
    }

    private var lastAnchor: Rect? = null
    private var touchable = false

    /**
     * While the pill listens or recognizes, the screen must not go dark: a long dictation
     * used to be cut off by the screen timeout, and the lock screen then hid the pill, so
     * Pisar looked frozen. Messages and actions do not hold the screen.
     */
    private var keepScreenOn = false

    private fun place(anchor: Rect?) {
        lastAnchor = anchor
        val metrics = service.resources.displayMetrics
        val gap = (10 * metrics.density).roundToInt()
        val w = view.pillWidth()
        val h = view.pillHeight()
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        if (anchor != null && !anchor.isEmpty) {
            params.x = (anchor.centerX() - w / 2).coerceIn(gap, max(gap, screenW - w - gap))
            // Above the field; below it when the field is at the very top.
            params.y = if (anchor.top - h - gap > gap * 4) anchor.top - h - gap else anchor.bottom + gap
        } else {
            params.x = (screenW - w) / 2
            params.y = screenH - h - (140 * metrics.density).roundToInt()
        }
        params.y = params.y.coerceIn(gap, max(gap, screenH - h - gap))
        params.flags =
            if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        params.flags =
            if (keepScreenOn) {
                params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            }
        try {
            if (attached) {
                windowManager.updateViewLayout(view, params)
            } else {
                windowManager.addView(view, params)
                attached = true
            }
        } catch (_: Exception) {
            attached = false
        }
    }

    private class PillView(
        context: Context,
    ) : View(context) {
        private enum class Mode { IDLE, LISTENING, PROCESSING, MESSAGE }

        private val density = resources.displayMetrics.density
        private val barCount = 13
        private val barWidth = 3 * density
        private val barGap = 2 * density
        private val padding = 12 * density
        private val height = 30 * density
        private val barsWidth = barCount * barWidth + (barCount - 1) * barGap

        // A pixel of margin above and below: on a burst a bar shoots up the whole pill.
        private val barMax = height - 2 * density

        // Like the Mac pill: always light, so it reads on any background, light or dark.
        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF5FFFFFF.toInt() }
        private val border =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = density
                color = 0x1F000000
            }
        private val textPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 13 * resources.displayMetrics.scaledDensity
                typeface = Typeface.DEFAULT
                color = 0xD9000000.toInt()
            }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAR_COLOR }

        private var mode = Mode.IDLE
        private var level: () -> Float = { 0f }
        private var text = ""
        private var textLayout: StaticLayout? = null
        private var action: String? = null
        private val actionGap = 16 * density
        private val actionPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14 * resources.displayMetrics.scaledDensity
                typeface = Typeface.DEFAULT_BOLD
                color = 0xFF2E6B30.toInt()
            }
        private val textPadV = 7 * density
        private val heights = FloatArray(barCount)

        // The Mac wave, frame for frame: one step is 1/60 s, whatever the screen's refresh rate.
        private var smoothLevel = 0f
        private val gain = FloatArray(barCount) { 1f }
        private val lag = IntArray(barCount)
        private var burst = -1 // steps since a burst began; -1: the bars just follow the level
        private var sinceRoll = 0
        private var lastFrameNanos = 0L
        private var pendingSteps = 0f

        private val frame =
            object : Runnable {
                override fun run() {
                    if (mode != Mode.LISTENING && mode != Mode.PROCESSING) return
                    tick()
                    invalidate()
                    postOnAnimation(this)
                }
            }

        fun pillWidth(): Int =
            when (mode) {
                Mode.MESSAGE -> (2 * padding + messageWidth()).roundToInt()
                else -> (2 * padding + barsWidth).roundToInt()
            }

        fun pillHeight(): Int =
            if (mode == Mode.MESSAGE) {
                // With a button it is a touch target: at least 40 dp tall.
                val minHeight = if (action != null) 40 * density else height
                max(minHeight, (textLayout?.height ?: 0) + 2 * textPadV).roundToInt()
            } else {
                height.roundToInt()
            }

        private fun messageWidth(): Float {
            val layout = textLayout ?: return 0f
            val textWidth = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
            return textWidth + (action?.let { actionPaint.measureText(it) + actionGap } ?: 0f)
        }

        fun listen(level: () -> Float) {
            this.level = level
            mode = Mode.LISTENING
            heights.fill(0f)
            smoothLevel = 0f
            burst = -1
            sinceRoll = 0
            restart()
        }

        fun process() {
            mode = Mode.PROCESSING
            removeCallbacks(frame)
            requestLayout()
            invalidate()
        }

        fun message(
            text: String,
            action: String? = null,
        ) {
            removeCallbacks(frame)
            this.text = text
            this.action = action
            // Long reasons (the Brain's especially) wrap onto a few lines instead of being cut.
            val actionRoom = action?.let { actionPaint.measureText(it) + actionGap } ?: 0f
            val maxWidth = (resources.displayMetrics.widthPixels * 0.8f - 2 * padding - actionRoom).roundToInt()
            textLayout =
                StaticLayout.Builder
                    .obtain(text, 0, text.length, textPaint, maxWidth)
                    .setMaxLines(4)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
            mode = Mode.MESSAGE
            requestLayout()
            invalidate()
        }

        fun stop() {
            removeCallbacks(frame)
            mode = Mode.IDLE
        }

        private fun restart() {
            removeCallbacks(frame)
            lastFrameNanos = 0L
            pendingSteps = 0f
            requestLayout()
            postOnAnimation(frame)
        }

        private fun tick() {
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) pendingSteps += (now - lastFrameNanos) / STEP_NANOS
            lastFrameNanos = now
            // After a stall, catch up by a few steps at most instead of replaying it all.
            pendingSteps = pendingSteps.coerceAtMost(4f)
            while (pendingSteps >= 1f) {
                step(level().coerceIn(0f, 1f))
                pendingSteps -= 1f
            }
        }

        /** New spread of the bars: the louder the voice, the wider the heights and the tighter the timing. */
        private fun roll() {
            val low = 0.75f - 0.55f * smoothLevel
            val spread = max(3, BURST_SPREAD - (6 * smoothLevel).toInt())
            for (i in 0 until barCount) {
                gain[i] = low + (1f - low) * Math.random().toFloat()
                lag[i] = (Math.random() * (spread + 1)).toInt()
            }
            burst = 0
            sinceRoll = 0
        }

        /**
         * One 1/60 s step. Up almost at once (the voice must hit the bar in the same frame), down
         * slower, or the bars would collapse between syllables and the wave would flicker.
         */
        private fun step(target: Float) {
            // Level is already on the dB scale (0..1), see AudioRecorder.levelOf.
            val rise = target - smoothLevel
            smoothLevel += rise * (if (rise > 0) RISE_RATE else FALL_RATE)
            sinceRoll++

            // A burst: every bar takes a new share of the level and a new delay, once per burst,
            // not every frame: per-frame randomness reads as trembling, a single one as a live voice.
            if (rise > BURST_TRIGGER && burst < 0) {
                roll()
            } else if (smoothLevel > LIVELY_FROM && sinceRoll >= max(4, (18 - 15 * smoothLevel).toInt())) {
                // While the voice is loud the bars reshuffle by themselves, so a shout looks as lively as it sounds.
                roll()
            }

            for (i in 0 until barCount) {
                // A bar whose turn has not come yet stays put: that is what makes a burst look uneven.
                if (burst >= 0 && burst < lag[i]) continue
                val goal = smoothLevel * shape(i) * gain[i]
                val speed = if (goal > heights[i]) RISE_RATE else FALL_RATE + FALL_LOUD * smoothLevel
                heights[i] += (goal - heights[i]) * speed
            }

            if (burst >= 0) burst = if (burst < BURST_SPREAD) burst + 1 else -1
        }

        /** A hill: the middle higher than the edges, so the pill reads as a wave, not a fence. */
        private fun shape(i: Int): Float = 0.72f + 0.28f * sin(Math.PI.toFloat() * (i + 0.5f) / barCount)

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            // The window is WRAP_CONTENT; add room for the border.
            setMeasuredDimension(pillWidth() + 2, pillHeight() + 2)
        }

        override fun onDraw(canvas: Canvas) {
            if (mode == Mode.IDLE) return
            val w = pillWidth().toFloat()
            val h = pillHeight().toFloat()
            val r = RectF(1f, 1f, w + 1f, h + 1f)
            val radius = height / 2
            canvas.drawRoundRect(r, radius, radius, background)
            canvas.drawRoundRect(r, radius, radius, border)
            if (mode == Mode.MESSAGE) {
                val layout = textLayout ?: return
                canvas.save()
                canvas.translate(r.left + padding, r.centerY() - layout.height / 2f)
                layout.draw(canvas)
                canvas.restore()
                action?.let {
                    val y = r.centerY() - (actionPaint.descent() + actionPaint.ascent()) / 2
                    canvas.drawText(it, r.right - padding - actionPaint.measureText(it), y, actionPaint)
                }
                return
            }
            // While recognizing, the bars stand still and turn blue: the work is not over yet.
            barPaint.color = if (mode == Mode.PROCESSING) BUSY_COLOR else BAR_COLOR
            var x = r.left + padding
            for (i in 0 until barCount) {
                // At rest a bar is a dot, not a lying oval: its height never goes below its width.
                val sway = if (mode == Mode.PROCESSING) BUSY_BAR else heights[i]
                val h = max(barWidth, barMax * sway)
                val top = r.centerY() - h / 2
                canvas.drawRoundRect(RectF(x, top, x + barWidth, top + h), barWidth / 2, barWidth / 2, barPaint)
                x += barWidth + barGap
            }
        }

        private companion object {
            const val STEP_NANOS = 1_000_000_000f / 60
            const val RISE_RATE = 0.75f // up: ~30 ms to the target
            const val FALL_RATE = 0.13f // down on quiet speech: ~120 ms
            const val FALL_LOUD = 0.45f // added to the fall at full volume
            const val BURST_TRIGGER = 0.1f
            const val BURST_SPREAD = 9 // steps the bars may drift apart within a burst, 150 ms
            const val LIVELY_FROM = 0.35f
            const val BUSY_BAR = 0.35f
            const val BAR_COLOR = 0xC7000000.toInt() // black at 78%, as on the Mac
            const val BUSY_COLOR = 0xFF007AFF.toInt() // the Mac's system blue
        }
    }
}
