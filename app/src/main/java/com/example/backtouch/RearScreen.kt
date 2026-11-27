package com.example.backtouch

import android.app.Activity
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Wraps Jetpack's rear-display presentation API (the camera's dual-screen mode).
 * Samsung Folds expose ONE logical display, so this is how the trackpad reaches the
 * cover screen while the phone is unfolded. Android only keeps it on while the
 * activity that started it is in front, which is why [CoverHostActivity] exists.
 */
@OptIn(ExperimentalWindowApi::class)
class RearScreen(private val activity: Activity, private val listener: Listener) {

    interface Listener {
        fun onAvailability(available: Boolean, message: String) {}
        fun onSession(running: Boolean, error: String?) {}
    }

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
                val available = status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE ||
                    status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE
                listener.onAvailability(
                    available,
                    when {
                        rear == null -> "This phone doesn't support cover screen mode"
                        available -> "Ready"
                        else -> "Unfold your phone to start"
                    }
                )
            }
        }
    }

    /** Shows [view] on the cover screen. */
    fun present(view: () -> android.view.View) {
        if (session != null) return
        val area = rear ?: run { listener.onSession(false, "Unfold your phone to start"); return }
        try {
            controller.presentContentOnWindowArea(
                area.token, activity, activity.mainExecutor,
                object : WindowAreaPresentationSessionCallback {
                    override fun onSessionStarted(s: WindowAreaSessionPresenter) {
                        session = s
                        s.setContentView(view())
                        listener.onSession(true, null)
                    }
                    override fun onSessionEnded(t: Throwable?) {
                        session = null
                        listener.onSession(false, t?.message)
                    }
                    override fun onContainerVisibilityChanged(isVisible: Boolean) {}
                }
            )
        } catch (t: Throwable) {
            listener.onSession(false, t.message ?: t.javaClass.simpleName)
        }
    }

    fun close() {
        session?.close()
    }

    fun stop() {
        close()
        scope.cancel()
    }
}
