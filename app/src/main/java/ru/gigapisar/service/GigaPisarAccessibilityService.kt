package ru.gigapisar.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.gigapisar.MainActivity
import ru.gigapisar.R
import ru.gigapisar.audio.AudioRecorder
import ru.gigapisar.brain.Brain
import ru.gigapisar.brain.BrainException
import ru.gigapisar.brain.BrainProviders
import ru.gigapisar.brain.KeyVault
import ru.gigapisar.brain.brainRussian
import ru.gigapisar.insertion.TextInserter
import ru.gigapisar.model.ModelManager
import ru.gigapisar.overlay.FabPreview
import ru.gigapisar.overlay.OverlayManager
import ru.gigapisar.overlay.RecordingPill
import ru.gigapisar.settings.AppLanguage
import ru.gigapisar.settings.InsertionMode
import ru.gigapisar.settings.SettingsRepository
import ru.gigapisar.speech.GigaAmOnnxRecognizer
import ru.gigapisar.update.Updates

class GigaPisarAccessibilityService : AccessibilityService() {
    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate,
        )

    private val audioRecorder =
        AudioRecorder(
            onTimeout = {
                handleRecordingStop()
            },
        )

    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private lateinit var modelManager:
        ModelManager

    private lateinit var recognizer:
        GigaAmOnnxRecognizer

    private lateinit var inserter:
        TextInserter

    private lateinit var overlay:
        OverlayManager

    private lateinit var pill:
        RecordingPill

    /** Checked at most every few seconds: validating the model reads its files. */
    private var modelReady = false
    private var modelCheckedAt = 0L

    private var insertionMode =
        InsertionMode.TEXT_FIELD

    private var virtualButtonEnabled = true
    private var volumeKeyEnabled = true
    private var vibrationEnabled = true
    private var noClipboard = false
    private var fabHiddenApps: Set<String> = emptySet()
    private var fabScale = 1f
    private var fabPreviewing = false

    @Volatile
    private var brainSettings = SettingsRepository.BrainSettings()

    private var focusedNode:
        AccessibilityNodeInfo? = null

    private var recordingJob: Job? = null

    private var volumeKeyPressed = false
    private var volumeKeyHoldTriggered = false
    private var volumeKeyStartedRecording = false

    @Volatile
    private var recording = false

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val volumeKeyHoldRunnable =
        Runnable {
            if (!volumeKeyPressed) {
                return@Runnable
            }

            volumeKeyHoldTriggered = true

            if (!recording && recordingJob?.isActive != true) {
                handleRecordingStart()
                volumeKeyStartedRecording = recording
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()

        modelManager =
            ModelManager(this)

        recognizer =
            GigaAmOnnxRecognizer(
                this,
                modelManager,
            )

        inserter =
            TextInserter(this)

        pill =
            RecordingPill(this)

        // A minute after start: the phone has settled and has network by then.
        mainHandler.postDelayed(updateCheck, 60_000)

        overlay =
            OverlayManager(
                this,
                onRecordingStart = {
                    handleRecordingStart(fromButton = true)
                },
                onRecordingStop = {
                    handleRecordingStop()
                },
                onRecordingCancel = {
                    handleRecordingCancel()
                },
                onLocked = {
                    buzz()
                },
            )

        serviceScope.launch {
            SettingsRepository
                .insertionMode(this@GigaPisarAccessibilityService)
                .collectLatest { mode ->

                    insertionMode = mode

                    if (mode == InsertionMode.TEXT_FIELD) {
                        updateFocusedNode()
                    } else {
                        updateButtonVisibility()
                    }
                }
        }

        serviceScope.launch {
            SettingsRepository
                .fabHiddenApps(this@GigaPisarAccessibilityService)
                .collectLatest { apps ->
                    fabHiddenApps = apps
                    updateButtonVisibility()
                }
        }

        serviceScope.launch {
            SettingsRepository
                .fabScale(this@GigaPisarAccessibilityService)
                .collectLatest { scale ->
                    fabScale = scale
                    if (!fabPreviewing) overlay.setScale(scale)
                }
        }

        serviceScope.launch {
            // While the size slider moves, the real button shows at that size; a moment after it
            // stops, back to the saved size and the usual rules.
            FabPreview.scale.collectLatest { scale ->
                if (scale != null) {
                    fabPreviewing = true
                    overlay.setScale(scale)
                    updateButtonVisibility()
                } else if (fabPreviewing) {
                    delay(FAB_PREVIEW_LINGER_MS)
                    fabPreviewing = false
                    overlay.setScale(fabScale)
                    updateButtonVisibility()
                }
            }
        }

        serviceScope.launch {
            SettingsRepository
                .virtualButtonVisible(this@GigaPisarAccessibilityService)
                .collectLatest { visible ->
                    virtualButtonEnabled = visible
                    updateButtonVisibility()
                }
        }

        serviceScope.launch {
            SettingsRepository
                .volumeKeyEnabled(this@GigaPisarAccessibilityService)
                .collectLatest { enabled -> volumeKeyEnabled = enabled }
        }

        serviceScope.launch {
            SettingsRepository
                .noClipboard(this@GigaPisarAccessibilityService)
                .collectLatest { enabled -> noClipboard = enabled }
        }

        serviceScope.launch {
            SettingsRepository
                .vibrationEnabled(this@GigaPisarAccessibilityService)
                .collectLatest { enabled -> vibrationEnabled = enabled }
        }

        serviceScope.launch {
            SettingsRepository
                .brain(this@GigaPisarAccessibilityService)
                .collectLatest { settings -> brainSettings = settings }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        // Turned off in settings: the key is plain volume again. A press that began
        // while it was on still gets its release, so a recording never hangs.
        if (!volumeKeyEnabled && !volumeKeyPressed) {
            return false
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (volumeKeyPressed) {
                    true
                } else if (event.repeatCount != 0) {
                    false
                } else if (insertionMode == InsertionMode.TEXT_FIELD && findFocusedEditable() == null) {
                    // No text field open: the key is an ordinary volume key, Android handles it.
                    false
                } else {
                    volumeKeyPressed = true
                    volumeKeyHoldTriggered = false
                    volumeKeyStartedRecording = false
                    mainHandler.postDelayed(
                        volumeKeyHoldRunnable,
                        VOLUME_RECORDING_HOLD_DELAY_MS,
                    )
                    true
                }
            }

            KeyEvent.ACTION_UP -> {
                if (!volumeKeyPressed) {
                    return false
                }

                mainHandler.removeCallbacks(volumeKeyHoldRunnable)
                volumeKeyPressed = false

                if (volumeKeyHoldTriggered) {
                    if (volumeKeyStartedRecording) {
                        handleRecordingStop()
                    }
                } else {
                    // A short press in a text field is an ordinary volume-down. The stream is named
                    // outright: some firmwares (Vivo) ignore "let Android pick" from a service.
                    val inCall =
                        audioManager.mode == AudioManager.MODE_IN_CALL ||
                            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
                    audioManager.adjustStreamVolume(
                        if (inCall) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI,
                    )
                }

                volumeKeyHoldTriggered = false
                volumeKeyStartedRecording = false
                true
            }

            else -> volumeKeyPressed
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // The undo offer goes away once the user types or leaves; not on our own insertion.
        // The "Copy" offer stays until its time is up or the user leaves the app, not on typing;
        // events from Pisar's own windows (the pill itself) never count.
        if (pill.showsAction &&
            event.packageName?.toString() != packageName &&
            SystemClock.uptimeMillis() - undoShownAt > 800 &&
            (
                (pillOffersUndo && event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )
        ) {
            pill.hide()
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            -> {
                if (insertionMode == InsertionMode.TEXT_FIELD) {
                    updateFocusedNode(event)
                }
            }
        }
    }

    private fun updateFocusedNode(event: AccessibilityEvent? = null) {
        val node = findFocusedEditable(event)

        if (focusedNode != node) {
            recycleNode(focusedNode)
            focusedNode = node
        }
        updateButtonVisibility()
    }

    private fun updateButtonVisibility() {
        val shouldShow =
            fabPreviewing ||
                // A hands-free recording is ended only with the button: it stays until then.
                recording && overlay.handsFree ||
                virtualButtonEnabled &&
                (
                    insertionMode == InsertionMode.CLIPBOARD ||
                        focusedNode != null
                ) &&
                !hiddenInCurrentApp()

        overlay.setButtonVisible(shouldShow)

        val now = SystemClock.elapsedRealtime()
        if (now - modelCheckedAt > 3000) {
            modelCheckedAt = now
            modelReady = modelManager.isInstalled()
        }
        overlay.setAvailable(modelReady)
    }

    /** The app on screen is one where the user chose to hide the floating button. */
    private fun hiddenInCurrentApp(): Boolean {
        if (fabHiddenApps.isEmpty()) return false
        val pkg =
            focusedNode?.packageName?.toString()
                ?: try {
                    rootInActiveWindow?.packageName?.toString()
                } catch (_: Exception) {
                    null
                }
        return pkg != null && pkg in fabHiddenApps
    }

    private fun findFocusedEditable(event: AccessibilityEvent? = null): AccessibilityNodeInfo? {
        val eventSource = event?.source
        if (eventSource != null && eventSource.isEditable && eventSource.isEnabled && eventSource.isFocused) {
            return eventSource
        }

        return try {
            val focused =
                findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

            if (focused != null && focused.isEditable && focused.isEnabled) {
                focused
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                node?.recycle()
            } catch (_: Throwable) {
                // Ignore already recycled
            }
        }
    }

    private fun handleRecordingStart(fromButton: Boolean = false) {
        if (recording) {
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyUser(
                ui().getString(
                    R.string.microphone_required,
                ),
            )

            overlay.setIdle()
            return
        }

        if (!modelManager.isInstalled()) {
            notifyUser(
                ui().getString(
                    R.string.model_required,
                ),
            )

            overlay.setIdle()
            openAppForSetup()
            return
        }

        if (!audioRecorder.start()) {
            notifyUser(
                ui().getString(
                    R.string.audio_record_error,
                ),
            )

            overlay.setIdle()
            return
        }

        recording = true
        overlay.setLevelSource { audioRecorder.level }
        if (fromButton) {
            // The strip next to the button shows the time and the way to cancel.
            overlay.setRecording()
        } else {
            overlay.setRecording(withHints = false)
            pill.showListening(focusedFieldBounds()) { audioRecorder.level }
        }
        buzz()
    }

    /** A slide toward "cancel" or a tap on it: the recording is thrown away, nothing is inserted. */
    private fun handleRecordingCancel() {
        if (!recording) {
            overlay.setIdle()
            return
        }
        recording = false
        audioRecorder.cancel()
        overlay.setIdle()
        pill.hide()
        buzz()
        updateButtonVisibility()
    }

    private fun handleRecordingStop() {
        if (!recording) {
            overlay.setIdle()
            return
        }

        recording = false

        overlay.setProcessing()
        pill.showProcessing()
        buzz()

        recordingJob?.cancel()

        recordingJob =
            serviceScope.launch(
                Dispatchers.IO,
            ) {
                try {
                    val audio =
                        audioRecorder.stop()

                    if (
                        audio.size <
                        AudioRecorder.SAMPLE_RATE / 5
                    ) {
                        // A quick tap is not a mistake worth a message: just go back to idle.
                        withContext(Dispatchers.Main) {
                            pill.hide()
                            overlay.setIdle()
                        }
                        return@launch
                    }

                    val recognized =
                        recognizer.transcribe(
                            audio,
                        )
                    // A password is never sent to a cloud service.
                    val brained = if (focusedNode?.isPassword == true) BrainOutcome(recognized) else applyBrain(recognized)
                    val text = brained.text
                    val brainFailure = brained.failure

                    withContext(
                        Dispatchers.Main,
                    ) {
                        var inserted = false
                        var insertion: TextInserter.Insertion? = null
                        if (text.isBlank()) {
                            notifyUser(
                                ui().getString(
                                    R.string.empty_transcription,
                                ),
                            )
                        } else {
                            when (
                                insertionMode
                            ) {
                                InsertionMode.CLIPBOARD -> {
                                    inserter.putToClipboard(
                                        text,
                                    )
                                    inserted = true
                                }

                                InsertionMode.TEXT_FIELD -> {
                                    insertion = inserter.insertIntoFocusedField(focusedNode, text, allowPaste = !noClipboard)
                                    inserted = insertion != null

                                    if (!inserted) {
                                        // The text did not go in: it is not left on the clipboard, it is offered on request.
                                        undoShownAt = SystemClock.uptimeMillis()
                                        pillOffersUndo = false
                                        pill.showAction(
                                            ui().getString(if (noClipboard) R.string.no_direct_insert else R.string.paste_failed),
                                            ui().getString(R.string.copy_text),
                                            focusedFieldBounds(),
                                            8000,
                                        ) { inserter.putToClipboard(text) }
                                    } else if (brainFailure == null && brained.original == null) {
                                        pill.hide()
                                    }
                                }
                            }
                        }

                        val original = brained.original
                        if (brainFailure != null && text.isNotBlank()) {
                            // The text is in as recognized; say why the Brain did not edit it.
                            notifyUser(ui().getString(R.string.brain_failed, brainFailure), 6000)
                        } else if (inserted && original != null) {
                            offerUndo(text, original, insertion)
                        } else if (insertionMode == InsertionMode.CLIPBOARD && text.isNotBlank()) {
                            notifyUser(ui().getString(R.string.copied_to_clipboard))
                        }
                        overlay.setIdle()
                    }
                } catch (error: Throwable) {
                    withContext(
                        Dispatchers.Main,
                    ) {
                        val message =
                            error.message
                                ?: error.javaClass.simpleName

                        notifyUser(
                            ui().getString(
                                R.string.transcription_error,
                                message,
                            ),
                        )

                        overlay.setIdle()
                    }
                }
            }
    }

    /** What goes into the field; [original] is set when the Brain changed the text, for "Вернуть". */
    private class BrainOutcome(
        val text: String,
        val failure: String? = null,
        val original: String? = null,
    )

    /**
     * Passes the recognized text through the Brain when it is on: a command at the end
     * ("…Писарь, сделай короче") always, every take only with "edit on the fly". Blocking,
     * called off the main thread. On failure returns the text to insert as is and the reason.
     */
    private fun applyBrain(text: String): BrainOutcome {
        val settings = brainSettings
        if (!settings.enabled || text.isBlank()) return BrainOutcome(text)
        val provider = BrainProviders.byId(settings.providerId) ?: return BrainOutcome(text)
        val model = settings.model ?: return BrainOutcome(text)
        val key = KeyVault.load(this, provider.id) ?: return BrainOutcome(text)
        val parsed = Brain.parseCommand(text)
        if (parsed == null && !settings.everyTake) return BrainOutcome(text)
        // A failed command still puts in what was said before "Писарь".
        val body = parsed?.first ?: text
        return try {
            val answer = Brain.transform(provider, key, model, body, parsed?.second)
            BrainOutcome(answer, original = body.takeIf { it != answer })
        } catch (error: BrainException) {
            BrainOutcome(body, failure = error.message ?: "")
        } catch (_: Exception) {
            BrainOutcome(body, failure = ui().getString(R.string.brain_failed_unknown))
        }
    }

    /** When the undo offer went up: our own text change right after must not dismiss it. */
    private var undoShownAt = 0L
    private var pillOffersUndo = false

    /**
     * "Мозг поправил · Вернуть" above the field for a few seconds. A tap puts back what was
     * dictated; if the field changed meanwhile, nothing is touched and the original goes to
     * the clipboard instead.
     */
    private fun offerUndo(
        answer: String,
        original: String,
        insertion: TextInserter.Insertion?,
    ) {
        undoShownAt = SystemClock.uptimeMillis()
        pillOffersUndo = true
        pill.showAction(
            ui().getString(R.string.brain_done),
            ui().getString(R.string.brain_undo),
            focusedFieldBounds(),
            UNDO_OFFER_MS,
        ) {
            val restored = insertion != null && inserter.replaceInserted(insertion, answer, original)
            if (!restored) {
                inserter.putToClipboard(original)
                notifyUser(
                    ui().getString(if (insertion == null) R.string.brain_undo_clipboard else R.string.brain_undo_changed),
                    4000,
                )
            }
        }
    }

    /**
     * Strings in the language picked in the settings. Taken fresh each time: the service
     * lives for days, and a language switched meanwhile should show up at once.
     */
    private fun ui(): Context {
        brainRussian = AppLanguage.isRussian(this)
        return AppLanguage.wrap(applicationContext)
    }

    /** A look for a new version at start and then every few hours, while the service lives. */
    private val updateCheck =
        object : Runnable {
            override fun run() {
                if (Updates.due(this@GigaPisarAccessibilityService)) {
                    serviceScope.launch(Dispatchers.IO) {
                        Updates
                            .check(
                                this@GigaPisarAccessibilityService,
                            )?.let { Updates.notifyOnce(this@GigaPisarAccessibilityService, it) }
                    }
                }
                mainHandler.postDelayed(this, UPDATE_TICK_MS)
            }
        }

    override fun onInterrupt() {
        cancelVolumeKeyGesture()
        recording = false
        audioRecorder.cancel()
        overlay.setIdle()
        pill.hide()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(updateCheck)
        cancelVolumeKeyGesture()
        recording = false

        audioRecorder.cancel()

        recordingJob?.cancel()

        recycleNode(focusedNode)
        focusedNode = null

        overlay.remove()

        recognizer.close()

        serviceScope.cancel()

        super.onDestroy()
    }

    /** Errors and hints of the recording flow: on the pill, next to where the text goes. */
    private fun notifyUser(
        text: String,
        millis: Long = 2600,
    ) {
        mainHandler.post { pill.showMessage(text, focusedFieldBounds(), millis) }
    }

    private fun focusedFieldBounds(): Rect? =
        focusedNode?.let { node ->
            try {
                Rect().also(node::getBoundsInScreen)
            } catch (_: Exception) {
                null
            }
        }

    /** A short tick at the start and the end of a recording: dictation without looking at the screen. */
    private fun buzz() {
        if (!vibrationEnabled) return
        try {
            val vibrator =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = getSystemService(VibratorManager::class.java)
                    manager?.defaultVibrator ?: getSystemService(Vibrator::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Vibrator::class.java)
                } ?: return

            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30L)
                return
            }

            // areAllEffectsSupported exists since API 30: older devices get the plain pulse.
            val effect =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    when {
                        vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK) ==
                            Vibrator.VIBRATION_EFFECT_SUPPORT_YES -> {
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                        }

                        vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_TICK) ==
                            Vibrator.VIBRATION_EFFECT_SUPPORT_YES -> {
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        }

                        else -> {
                            VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
                        }
                    }
                } else {
                    VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
                }

            // vibrate(effect, VibrationAttributes) exists since API 33.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY),
                )
            } else {
                vibrator.vibrate(effect)
            }
        } catch (_: Exception) {
            // No vibrator: nothing to do.
        }
    }

    /** Opens the app on its setup steps (e.g. the model is missing). */
    private fun openAppForSetup() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } catch (_: Exception) {
            // The pill already said what to do.
        }
    }

    private fun showToast(text: String) {
        mainHandler.post {
            Toast
                .makeText(
                    this,
                    text,
                    Toast.LENGTH_SHORT,
                ).show()
        }
    }

    private fun cancelVolumeKeyGesture() {
        mainHandler.removeCallbacks(volumeKeyHoldRunnable)
        volumeKeyPressed = false
        volumeKeyHoldTriggered = false
        volumeKeyStartedRecording = false
    }

    companion object {
        // An ordinary tap lasts 100-200 ms; recording starts only on a deliberate hold.
        private const val VOLUME_RECORDING_HOLD_DELAY_MS = 350L

        /** How long the size preview stays after the slider stops. */
        private const val FAB_PREVIEW_LINGER_MS = 1200L

        /** How often the service wakes to see whether a new version is due (the check itself runs every 6 hours). */
        private const val UPDATE_TICK_MS = 60 * 60 * 1000L

        /** How long "Вернуть" stays offered after a Brain edit. */
        private const val UNDO_OFFER_MS = 6000L
    }
}
