package com.example.backtouch

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot

/** Full-screen touch surface. Meant to be launched on the cover display. */
class TrackpadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TrackpadView(this).apply { setBackgroundColor(Color.rgb(24, 24, 28)) })
    }
}

private class TrackpadView(ctx: Context) : View(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val sensitivity = 1.8f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = 0f
    private var maxPointers = 0
    private var longFired = false
    private var scrollAcc = 0f

    private val longPress = Runnable {
        longFired = true
        CursorService.instance?.longPress()
    }

    private fun centroid(e: MotionEvent, skip: Int): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        return if (n == 0) 0f to 0f else sx / n to sy / n
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val svc = CursorService.instance
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (x, y) = centroid(e, -1)
                lastX = x; lastY = y
                downTime = e.eventTime; moved = 0f; maxPointers = 1
                longFired = false; scrollAcc = 0f
                postDelayed(longPress, 600)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                removeCallbacks(longPress)
                val (x, y) = centroid(e, -1); lastX = x; lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val (x, y) = centroid(e, e.actionIndex); lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val (x, y) = centroid(e, -1)
                val dx = x - lastX; val dy = y - lastY
                lastX = x; lastY = y
                moved += hypot(dx, dy)
                if (moved > slop) removeCallbacks(longPress)
                if (e.pointerCount == 1) {
                    svc?.moveBy(dx * sensitivity, dy * sensitivity)
                } else {
                    scrollAcc += dy
                    if (abs(scrollAcc) > 60f) {
                        svc?.scrollBy(scrollAcc * 2f)
                        scrollAcc = 0f
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (maxPointers == 1 && moved < slop && !longFired &&
                    e.eventTime - downTime < 250) svc?.tap()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }
}
