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
class RearScreen(private val activity: Activity, private val onStatus: (String) -> Unit) {
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
                onStatus(
                    when {
                        areas.isEmpty() -> "Cover screen API: no screen areas reported by this phone"
                        rear == null -> "Cover screen API: ${areas.size} area(s), none rear-facing"
                        else -> "Cover screen API: present mode = $status"
                    }
                )
            }
        }
    }

    fun toggle() {
        session?.let { it.close(); return }
        val area = rear ?: run { onStatus("Cover screen API: no rear screen available"); return }
        try {
            controller.presentContentOnWindowArea(
                area.token, activity, activity.mainExecutor,
                object : WindowAreaPresentationSessionCallback {
                    override fun onSessionStarted(s: WindowAreaSessionPresenter) {
                        session = s
                        s.setContentView(TrackpadView(s.context))
                        onStatus("Trackpad is on the cover screen")
                    }
                    override fun onSessionEnded(t: Throwable?) {
                        session = null
                        onStatus("Cover screen session ended" + (t?.let { ": ${it.message}" } ?: ""))
                    }
                    override fun onContainerVisibilityChanged(isVisible: Boolean) {
                        onStatus("Cover screen content visible = $isVisible")
                    }
                }
            )
        } catch (t: Throwable) {
            onStatus("Cover screen API failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun stop() {
        session?.close()
        scope.cancel()
    }
}
