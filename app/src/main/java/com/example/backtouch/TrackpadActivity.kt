package com.example.backtouch

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Full-screen touch surface. Meant to be launched on the cover display. */
class TrackpadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TrackpadView(this).apply { setBackgroundColor(Color.rgb(24, 24, 28)) })
    }
}

/**
 * Gestures:
 *  1 finger move      -> move cursor (with acceleration)
 *  1 finger tap       -> click (tap twice quickly = double-click)
 *  hold, then move    -> drag
 *  hold, release      -> long press
 *  2 finger move      -> scroll
 *  2 finger tap       -> long press ("right click")
 *  3 finger tap       -> pause / resume
 * Palm rejection: touches that start near an edge, or with a large contact area, are ignored.
 */
private class TrackpadView(ctx: Context) : View(ctx) {
    private val settings = Settings(ctx)
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var downTime = 0L
    private var moved = 0f
    private var maxPointers = 0
    private var holdArmed = false
    private var ignoring = false
    private var scrollAcc = 0f
    private var paused = false

    // Read once per gesture so slider changes apply without restarting.
    private var sens = 1.8f
    private var accel = 0.6f
    private var invert = false

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; textSize = 18 * density; textAlign = Paint.Align.CENTER
    }

    private val holdTimer = Runnable {
        holdArmed = true
        moved = 0f
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val label = if (paused) "Paused - 3-finger tap to resume" else "BackTouch"
        c.drawText(label, width / 2f, height / 2f, textPaint)
    }

    private fun isPalm(e: MotionEvent): Boolean {
        for (i in 0 until e.pointerCount) if (e.getTouchMajor(i) > PALM_DP * density) return true
        return false
    }

    private fun inEdge(x: Float, y: Float): Boolean {
        val m = settings.edgePct / 100f
        return x < width * m || x > width * (1 - m) || y < height * m || y > height * (1 - m)
    }

    private fun centroid(e: MotionEvent, skip: Int): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        return if (n == 0) lastX to lastY else sx / n to sy / n
    }

    private fun cancelGesture() {
        removeCallbacks(holdTimer)
        ignoring = true
        CursorService.instance?.dragEnd()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val svc = CursorService.instance
        val action = e.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            sens = settings.sensitivity; accel = settings.acceleration; invert = settings.invertScroll
            lastX = e.x; lastY = e.y; lastT = e.eventTime
            downTime = e.eventTime; moved = 0f; maxPointers = 1
            holdArmed = false; scrollAcc = 0f
            ignoring = inEdge(e.x, e.y) || isPalm(e)
            if (!ignoring && !paused) postDelayed(holdTimer, HOLD_MS)
            return true
        }
        if (ignoring) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ignoring = false
            return true
        }

        when (action) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                removeCallbacks(holdTimer)
                if (isPalm(e)) { cancelGesture(); return true }
                val (x, y) = centroid(e, -1); lastX = x; lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val (x, y) = centroid(e, e.actionIndex); lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPalm(e)) { cancelGesture(); return true }
                val (x, y) = centroid(e, -1)
                val dx = x - lastX; val dy = y - lastY
                val dt = max(1L, e.eventTime - lastT)
                lastX = x; lastY = y; lastT = e.eventTime
                val dist = hypot(dx, dy)
                moved += dist
                if (!holdArmed && moved > slop) removeCallbacks(holdTimer)
                if (paused || svc == null) return true

                when (e.pointerCount) {
                    1 -> {
                        if (holdArmed && moved > slop && !svc.isDragging) svc.dragStart()
                        val speed = dist / dt / density          // dp per ms
                        val gain = sens * (1f + accel * speed)
                        svc.moveBy(dx * gain, dy * gain)
                    }
                    2 -> {
                        scrollAcc += if (invert) -dy else dy
                        if (abs(scrollAcc) > SCROLL_STEP_DP * density) {
                            svc.scrollBy(scrollAcc * 2f)
                            scrollAcc = 0f
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdTimer)
                val quick = e.eventTime - downTime < TAP_MS
                val still = moved < slop * maxPointers
                when {
                    maxPointers >= 3 && quick && still -> {
                        paused = !paused
                        svc?.setCursorVisible(!paused)
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        invalidate()
                    }
                    paused || svc == null -> Unit
                    svc.isDragging -> svc.dragEnd()
                    holdArmed && maxPointers == 1 -> svc.longPress()
                    maxPointers == 1 && quick && still -> svc.tap()
                    maxPointers == 2 && quick && still -> svc.longPress()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdTimer)
                svc?.dragEnd()
            }
        }
        return true
    }

    companion object {
        private const val HOLD_MS = 450L
        private const val TAP_MS = 250L
        private const val PALM_DP = 28f
        private const val SCROLL_STEP_DP = 20f
    }
}
