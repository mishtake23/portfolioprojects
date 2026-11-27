package com.example.backtouch

import android.app.Activity
import androidx.window.core.ExperimentalWindowApi
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Samsung Folds expose ONE logical display that switches between inner and cover
 * screen, so launching on a second display id is impossible. This uses the
 * Jetpack "present on rear display" API (dual-screen mode) to show the trackpad
 * on the cover screen while the phone is unfolded.
 */
@OptIn(ExperimentalWindowApi::class)
class RearScreen(
    private val activity: Activity,
    private val onStatus: (String) -> Unit,
    private val autoStart: () -> Boolean = { false },
) {
    private var autoStarted = false
    private val controller = WindowAreaController.getOrCreate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rear: WindowAreaInfo? = null
    private var session: WindowAreaSessionPresenter? = null

    val isPresenting get() = session != null

    fun start() {
        scope.launch {
            controller.windowAreaInfos.collect { areas ->
                rear = areas.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                val status = rear
                    ?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)
                    ?.status
                val available = status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE
                onStatus(
                    when {
                        session != null -> "Running on the cover screen"
                        areas.isEmpty() || rear == null -> "This phone doesn't support cover screen mode"
                        available -> "Ready - tap Start"
                        else -> "Unfold your phone to start"
                    }
                )
                if (available && session == null && !autoStarted && autoStart()) {
                    autoStarted = true
                    toggle()
                }
            }
        }
    }

    fun toggle() {
        session?.let { it.close(); return }
        val area = rear ?: run { onStatus("Unfold your phone to start"); return }
        try {
            controller.presentContentOnWindowArea(
                area.token, activity, activity.mainExecutor,
                object : WindowAreaPresentationSessionCallback {
                    override fun onSessionStarted(s: WindowAreaSessionPresenter) {
                        session = s
                        s.setContentView(TrackpadView(s.context))
                        onStatus("Running on the cover screen")
                    }
                    override fun onSessionEnded(t: Throwable?) {
                        session = null
                        onStatus(if (t == null) "Stopped" else "Stopped: ${t.message}")
                    }
                    override fun onContainerVisibilityChanged(isVisible: Boolean) {
                        if (!isVisible) onStatus("Cover screen is off - unfold your phone")
                    }
                }
            )
        } catch (t: Throwable) {
            onStatus("Couldn't start: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun stop() {
        session?.close()
        scope.cancel()
    }
}
