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
) {
    companion object {
        private const val POSITION_PREFERENCES = "overlay_position"
        private const val POSITION_X_KEY = "x"
        private const val POSITION_Y_KEY = "y"

        private const val BUTTON_SIZE_DP = 120
        private const val EDGE_MARGIN_DP = 0
        private const val TOP_MARGIN_DP = 96
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
    }

    fun attach() {
        if (attached) {
            return
        }

        restorePosition()

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
        button.setState(
            RecordingButton.State.IDLE,
        )
    }

    fun setRecording() {
        button.setState(
            RecordingButton.State.RECORDING,
        )
    }

    fun setProcessing() {
        button.setState(
            RecordingButton.State.PROCESSING,
        )
    }

    fun remove() {
        cancelPendingFrameUpdate()

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
