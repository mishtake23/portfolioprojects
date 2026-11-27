package com.example.backtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Draws a cursor on the DEFAULT display (the inner screen when unfolded) and
 * performs taps / scrolls at the cursor position via dispatchGesture.
 */
class CursorService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var cursor: View? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var x = 0f
    private var y = 0f
    private var w = 0
    private var h = 0

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = wm.currentWindowMetrics.bounds
        w = b.width(); h = b.height()
        x = w / 2f; y = h / 2f

        val size = 36
        val view = object : View(this) {
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f
            }
            override fun onDraw(c: Canvas) {
                val r = width / 2f - 3f
                c.drawCircle(width / 2f, height / 2f, r, fill)
                c.drawCircle(width / 2f, height / 2f, r, ring)
            }
        }
        lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        cursor = view
        wm.addView(view, lp)
        updateCursor()
    }

    private fun updateCursor() {
        lp.x = (x - lp.width / 2).toInt()
        lp.y = (y - lp.height / 2).toInt()
        cursor?.let { wm.updateViewLayout(it, lp) }
    }

    fun moveBy(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0f, (w - 1).toFloat())
        y = (y + dy).coerceIn(0f, (h - 1).toFloat())
        updateCursor()
    }

    fun tap(holdMs: Long = 50) {
        val p = Path().apply { moveTo(x, y) }
        dispatch(GestureDescription.StrokeDescription(p, 0, holdMs))
    }

    fun longPress() = tap(700)

    /** Swipe starting at the cursor; dy > 0 moves content down (finger moves down). */
    fun scrollBy(dy: Float) {
        val endY = (y + dy).coerceIn(1f, (h - 1).toFloat())
        val p = Path().apply { moveTo(x, y); lineTo(x, endY) }
        dispatch(GestureDescription.StrokeDescription(p, 0, 150))
    }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        cursor?.let { runCatching { wm.removeView(it) } }
        cursor = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: CursorService? = null
    }
}
