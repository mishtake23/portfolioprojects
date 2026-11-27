package com.example.backtouch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager

/** Starts / stops the cover-screen trackpad and tracks whether it is showing. */
object Cover {
    /** Trackpad is currently showing on the cover screen. */
    @Volatile var running = false

    /** Last problem, shown in the app. */
    @Volatile var lastError: String? = null

    @Volatile internal var host: CoverHostActivity? = null

    fun start(ctx: Context) {
        Prefs(ctx).coverActive = true
        lastError = null
        launchHost(ctx)
    }

    fun stop(ctx: Context) {
        Prefs(ctx).coverActive = false
        host?.finish()
        running = false
    }

    internal fun launchHost(ctx: Context) {
        ctx.startActivity(
            Intent(ctx, CoverHostActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        )
    }
}

/**
 * Invisible, untouchable activity whose only job is to hold the cover-screen session.
 * Touches and the picture on the inner screen pass straight through to the app below.
 * When another app comes to the front Android ends the session; this activity then
 * closes and [CursorService] brings it back a moment later ("auto-restart").
 */
class CoverHostActivity : Activity() {
    private lateinit var rear: RearScreen
    private var requested = false
    private val giveUp = Runnable { if (!rear.isPresenting) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cover.host = this
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        window.setDimAmount(0f)
        // Android 12+ blocks touches that pass through another app's window unless that
        // window is fully invisible (anti-clickjacking). Alpha 0 = invisible, so every tap
        // and swipe reaches the app underneath.
        window.attributes = window.attributes.apply { alpha = 0f }
        rear = RearScreen(this, object : RearScreen.Listener {
            override fun onAvailability(available: Boolean, message: String) {
                if (CursorService.instance?.folded == true) { finish(); return }
                if (available && !requested) {
                    requested = true
                    rear.present { TrackpadView(this@CoverHostActivity) }
                } else if (!available && !rear.isPresenting) {
                    finish() // folded: get out of the way, the service restarts us on unfold
                }
            }

            override fun onSession(running: Boolean, error: String?) {
                Cover.running = running
                if (!running) {
                    if (error != null) Cover.lastError = error
                    finish()
                }
            }
        })
        rear.start()
        window.decorView.postDelayed(giveUp, 4000)
    }

    override fun onStop() {
        super.onStop()
        // Another app came to the front: the session is gone anyway.
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(giveUp)
        rear.stop()
        if (Cover.host === this) Cover.host = null
        Cover.running = false
        val p = Prefs(this)
        if (p.coverActive && p.keepAliveLayer) CursorService.instance?.scheduleRelaunch()
        else if (p.coverActive) p.coverActive = false
        super.onDestroy()
    }
}
