package ru.gigapisar.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.roundToInt

class OverlayManager(
    private val service: AccessibilityService,
    onRecordingStart: () -> Unit,
    onRecordingStop: () -> Unit,
    private val onRecordingCancel: () -> Unit,
    private val onLocked: () -> Unit,
) {
    companion object {
        private const val POSITION_PREFERENCES = "overlay_position"
        private const val POSITION_X_KEY = "x"
        private const val POSITION_Y_KEY = "y"

        private const val BUTTON_SIZE_DP = 120
        private const val EDGE_MARGIN_DP = 0
        private const val TOP_MARGIN_DP = 96
        private const val CIRCLE_RADIUS_DP = 43
    }

    private val density =
        service.resources.displayMetrics.density

    private var scale = 1f

    private val buttonSize: Int
        get() = (BUTTON_SIZE_DP * density * scale).roundToInt()

    private val edgeMargin =
        (EDGE_MARGIN_DP * density).roundToInt()

    private val topMargin =
        (TOP_MARGIN_DP * density).roundToInt()

    private val windowManager =
        service.getSystemService(
            WindowManager::class.java,
        )

    private val button =
        RecordingButton(service)

    private val params =
        WindowManager
            .LayoutParams(
                buttonSize,
                buttonSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity =
                    Gravity.TOP or Gravity.START
            }

    private var attached = false

    /*
     * While recording: the strip with the time and "‹ ‹ Cancel" next to the button and the
     * lock above it, each in its own window that takes no touches (the strip takes a tap on
     * "Cancel" once hands-free). The button window was added first, so these never cover it:
     * they are placed beside it, not over it.
     */
    private val bar = RecordingBar(service)
    private val lock = LockBadge(service)
    private var chromeShown = false
    private var homeX = 0
    private var homeY = 0
    private var barMargin = (8 * density).roundToInt()

    private val barParams =
        WindowManager
            .LayoutParams(
                0,
                ((RecordingBar.HEIGHT_DP + 2 * RecordingBar.SHADOW_DP) * density).roundToInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }

    private val lockParams =
        WindowManager
            .LayoutParams(
                ((LockBadge.WIDTH_DP + 2 * LockBadge.SHADOW_DP) * density).roundToInt(),
                ((LockBadge.HEIGHT_DP + 2 * LockBadge.SHADOW_DP) * density).roundToInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }

/*
 * Position at the moment when the current drag starts.
 */
    private var dragStartX = 0
    private var dragStartY = 0

/*
 * Pointer position at the moment when the current drag starts.
 */
    private var dragPointerStartX = 0f
    private var dragPointerStartY = 0f

/*
 * Position that should be displayed on the next frame.
 */
    private var targetX = 0
    private var targetY = 0

/*
 * Prevents scheduling multiple frame callbacks.
 */
    private var frameUpdateScheduled = false

    private var pendingFrameUpdate: Runnable? = null

    init {
        button.onRecordingStart =
            onRecordingStart

        button.onRecordingStop =
            onRecordingStop

        button.onDragStart =
            ::beginDrag

        button.onDrag =
            ::moveBy

        button.onDragEnd =
            ::savePosition

        button.onSwipe = ::followSwipe

        button.onLock = {
            lock.locked = true
            lock.progress = 0f
            bar.handsFree = true
            barParams.flags = barParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            updateWindow(bar, barParams)
            onLocked()
        }

        button.onCancel = {
            hideChrome()
            onRecordingCancel()
        }

        bar.onCancel = {
            hideChrome()
            onRecordingCancel()
        }
    }

    fun attach() {
        if (attached) {
            return
        }

        restorePosition()
        syncTargets()

        try {
            windowManager.addView(
                button,
                params,
            )

            attached = true
        } catch (_: Exception) {
            attached = false
        }
    }

    fun setButtonVisible(visible: Boolean) {
        if (!attached && visible) {
            attach()
        }

        if (!attached) {
            return
        }

        if (visible) {
            button.showAnimated()
        } else {
            hideChrome()
            button.hideAnimated()
        }
    }

    /** Resizes the button (the settings slider); keeps its centre where it was and on screen. */
    fun setScale(value: Float) {
        if (value == scale) return
        val oldSize = buttonSize
        scale = value
        button.sizeScale = value
        val newSize = buttonSize
        params.width = newSize
        params.height = newSize
        val bounds = getScreenBounds()
        params.x =
            (params.x + (oldSize - newSize) / 2).coerceIn(edgeMargin, (bounds.width - newSize - edgeMargin).coerceAtLeast(edgeMargin))
        params.y =
            (params.y + (oldSize - newSize) / 2).coerceIn(edgeMargin, (bounds.height - newSize - edgeMargin).coerceAtLeast(edgeMargin))
        if (attached) {
            try {
                windowManager.updateViewLayout(button, params)
            } catch (_: Exception) {
                // The view may be on its way out.
            }
        }
    }

    /** Half-transparent while the app is not ready (no model yet): a tap then leads to setup. */
    fun setAvailable(available: Boolean) {
        button.dimmed = !available
    }

    /** Microphone level source for the pulse while recording. */
    fun setLevelSource(level: () -> Float) {
        button.level = level
    }

    fun setIdle() {
        hideChrome()
        button.setState(
            RecordingButton.State.IDLE,
        )
    }

    /** Hints (strip and lock) only when the button itself started the recording. */
    fun setRecording(withHints: Boolean = true) {
        button.setState(
            RecordingButton.State.RECORDING,
        )
        if (withHints) showChrome()
    }

    val handsFree: Boolean
        get() = button.handsFree

    /** Off in the settings: hold, speak, let go, as before; no slides and no strip. */
    var gestures: Boolean
        get() = button.gestures
        set(value) {
            button.gestures = value
        }

    fun setProcessing() {
        hideChrome()
        button.setState(
            RecordingButton.State.PROCESSING,
        )
    }

    /** Strip and lock next to the button; skipped while the button is hidden. */
    private fun showChrome() {
        if (!attached || button.visibility != android.view.View.VISIBLE) return
        hideChrome()
        homeX = params.x
        homeY = params.y

        val bounds = getScreenBounds()
        val size = buttonSize
        val centerX = homeX + size / 2
        val centerY = homeY + size / 2
        val left = centerX < bounds.width / 2
        // The middle of the screen is where "cancel" lies.
        button.inwardSign = if (left) 1f else -1f

        val barHeight = barParams.height
        val barWidth = if (left) bounds.width - barMargin - (homeX + size) else homeX - barMargin
        if (barWidth >= (140 * density).roundToInt()) {
            bar.side = if (left) 1 else -1
            barParams.width = barWidth
            barParams.x = if (left) homeX + size else barMargin
            barParams.y = centerY - barHeight / 2
            barParams.flags = barParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            bar.restart()
            addWindow(bar, barParams)
            button.barSide = if (left) 1 else -1
        }

        // The lock goes above the button, or below it when the button sits near the top.
        val gap = ((CIRCLE_RADIUS_DP * scale + 14) * density).roundToInt()
        val above = centerY - gap - lockParams.height
        val below = lockParams.height + gap + centerY <= bounds.height
        val down = above < statusBarHeight() && below
        lock.locked = false
        lock.progress = 0f
        lock.pointsDown = down
        button.lockSign = if (down) 1f else -1f
        lockParams.x = centerX - lockParams.width / 2
        lockParams.y = if (down) centerY + gap else above.coerceAtLeast(0)
        addWindow(lock, lockParams)
        chromeShown = true
    }

    /** Overlays are kept out of the status bar, so the lock must fit below it. */
    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else (24 * density).roundToInt()
    }

    private fun hideChrome() {
        if (!chromeShown) return
        chromeShown = false
        bar.handsFree = false
        removeWindow(bar)
        removeWindow(lock)
        moveButtonTo(homeX, homeY)
    }

    /**
     * Follows a slide that started on the button: toward "cancel" the button goes along with
     * the finger and the strip shortens in front of it; toward the lock it rises a little and
     * the arrow climbs. (0, 0) puts everything back.
     */
    private fun followSwipe(
        inward: Float,
        up: Float,
    ) {
        if (!chromeShown) return
        val shiftX = (inward * button.inwardSign).roundToInt()
        val rise = (minOf(up, 24 * density) * -button.lockSign).roundToInt()
        lock.progress = up / (80 * density * scale)
        bar.cancelProgress = inward / (110 * density * scale)
        button.barSide = if (rise != 0 || bar.parent == null) 0 else bar.side
        moveButtonTo(homeX + shiftX, homeY - rise)
        if (bar.parent != null) {
            if (bar.side < 0) {
                barParams.width = (homeX + shiftX - barMargin).coerceAtLeast(1)
            } else {
                barParams.x = homeX + buttonSize + shiftX
                barParams.width = (getScreenBounds().width - barMargin - barParams.x).coerceAtLeast(1)
            }
            updateWindow(bar, barParams)
        }
    }

    private fun moveButtonTo(
        x: Int,
        y: Int,
    ) {
        if (!attached || (params.x == x && params.y == y)) return
        params.x = x
        params.y = y
        targetX = x
        targetY = y
        updateWindow(button, params)
    }

    private fun addWindow(
        view: android.view.View,
        layout: WindowManager.LayoutParams,
    ) {
        try {
            windowManager.addView(view, layout)
        } catch (_: Exception) {
            // No overlay right now: recording works without the hints.
        }
    }

    private fun updateWindow(
        view: android.view.View,
        layout: WindowManager.LayoutParams,
    ) {
        if (view.parent == null) return
        try {
            windowManager.updateViewLayout(view, layout)
        } catch (_: Exception) {
            // The view may be on its way out.
        }
    }

    private fun removeWindow(view: android.view.View) {
        if (view.parent == null) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
            // Already gone.
        }
    }

    fun remove() {
        cancelPendingFrameUpdate()
        hideChrome()

        if (!attached) {
            return
        }

        try {
            windowManager.removeView(button)
        } catch (_: Exception) {
            // View may already have been removed.
        }

        attached = false
    }

    private fun restorePosition() {
        val preferences =
            service.getSharedPreferences(
                POSITION_PREFERENCES,
                Context.MODE_PRIVATE,
            )

        val bounds =
            getScreenBounds()

        val maxX =
            (bounds.width - buttonSize - edgeMargin)
                .coerceAtLeast(edgeMargin)

        val maxY =
            (bounds.height - buttonSize - edgeMargin)
                .coerceAtLeast(edgeMargin)

        params.x =
            preferences
                .getInt(
                    POSITION_X_KEY,
                    maxX,
                ).coerceIn(
                    edgeMargin,
                    maxX,
                )

        params.y =
            preferences
                .getInt(
                    POSITION_Y_KEY,
                    topMargin,
                ).coerceIn(
                    edgeMargin,
                    maxY,
                )
    }

    private fun syncTargets() {
        targetX = params.x
        targetY = params.y
    }

    private fun beginDrag() {
    /*
     * Save the exact position from which this drag started.
     */
        dragStartX = params.x
        dragStartY = params.y

    /*
     * The current RecordingButton implementation sends movement
     * deltas, so these values are not actually needed for the
     * calculation below. They are kept here to make the drag
     * coordinate system explicit and easy to extend.
     */
        dragPointerStartX = 0f
        dragPointerStartY = 0f

        targetX = params.x
        targetY = params.y
    }

    private fun moveBy(
        dx: Float,
        dy: Float,
    ) {
        if (!attached) {
            return
        }

        val bounds =
            getScreenBounds()

        val maxX =
            (bounds.width - buttonSize - edgeMargin)
                .coerceAtLeast(edgeMargin)

        val maxY =
            (bounds.height - buttonSize - edgeMargin)
                .coerceAtLeast(edgeMargin)

    /*
     * The RecordingButton provides movement since the previous
     * MotionEvent. Accumulate it in floating point and round only
     * once when calculating the final WindowManager position.
     *
     * This avoids repeated toInt() truncation.
     */
        val newX =
            targetX + dx.roundToInt()

        val newY =
            targetY + dy.roundToInt()

        targetX =
            newX.coerceIn(
                edgeMargin,
                maxX,
            )

        targetY =
            newY.coerceIn(
                edgeMargin,
                maxY,
            )

        scheduleFrameUpdate()
    }

    private fun scheduleFrameUpdate() {
        if (frameUpdateScheduled || !attached) {
            return
        }

        frameUpdateScheduled = true

        val update =
            Runnable {
                pendingFrameUpdate = null
                frameUpdateScheduled = false

                if (!attached) {
                    return@Runnable
                }

                if (
                    params.x == targetX &&
                    params.y == targetY
                ) {
                    return@Runnable
                }

                params.x = targetX
                params.y = targetY

                try {
                    windowManager.updateViewLayout(
                        button,
                        params,
                    )
                } catch (_: Exception) {
                    attached = false
                }
            }

        pendingFrameUpdate = update
        button.postOnAnimation(update)
    }

    private fun savePosition() {
    /*
     * There may still be one pending VSYNC update.
     *
     * Apply it synchronously before persisting the position,
     * otherwise the saved coordinates could lag behind the
     * actual final position.
     */
        applyPendingPosition()

        service
            .getSharedPreferences(
                POSITION_PREFERENCES,
                Context.MODE_PRIVATE,
            ).edit()
            .putInt(
                POSITION_X_KEY,
                params.x,
            ).putInt(
                POSITION_Y_KEY,
                params.y,
            ).apply()
    }

    private fun applyPendingPosition() {
        if (!attached) {
            return
        }

        params.x = targetX
        params.y = targetY

        try {
            windowManager.updateViewLayout(
                button,
                params,
            )
        } catch (_: Exception) {
            attached = false
        }

        frameUpdateScheduled = false
    }

    private fun cancelPendingFrameUpdate() {
        if (!frameUpdateScheduled) {
            return
        }

        pendingFrameUpdate?.let(button::removeCallbacks)
        pendingFrameUpdate = null
        frameUpdateScheduled = false
    }

    private fun getScreenBounds(): ScreenBounds {
        val metrics =
            service.resources.displayMetrics

        return ScreenBounds(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
        )
    }

    private data class ScreenBounds(
        val width: Int,
        val height: Int,
    )
}
