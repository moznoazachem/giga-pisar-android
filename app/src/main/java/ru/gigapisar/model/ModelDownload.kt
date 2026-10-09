package ru.gigapisar.model

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.gigapisar.R

/**
 * The model download, kept out of the screen that started it: turning the phone recreates
 * the activity and used to cancel the download with it. The screen only watches [state].
 */
object ModelDownload {
    sealed interface State {
        data object Idle : State

        data class Running(val percent: Int) : State

        data object Done : State

        data class Failed(val reason: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /** Starts the download unless one is already running. */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        _state.value = State.Running(0)
        job =
            scope.launch {
                val manager = ModelManager(app)
                _state.value =
                    try {
                        manager.download { value -> _state.value = State.Running(value) }
                        if (!manager.isInstalled()) {
                            State.Failed(app.getString(R.string.model_verification_failed))
                        } else {
                            State.Done
                        }
                    } catch (e: Throwable) {
                        if (e is java.net.UnknownHostException ||
                            e is java.net.ConnectException ||
                            e is java.net.SocketTimeoutException
                        ) {
                            // A DNS or connection failure reads like a server problem; it is the phone's network.
                            State.Failed(app.getString(R.string.download_no_network))
                        } else {
                            State.Failed(e.message ?: e.javaClass.simpleName)
                        }
                    }
            }
    }
}
