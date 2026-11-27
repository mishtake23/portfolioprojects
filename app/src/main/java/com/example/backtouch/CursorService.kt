package com.example.backtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

/**
 * Draws a mouse pointer on the inner screen and turns trackpad input into real touches
 * there: taps, long presses, drags and smooth finger-style scrolling, plus Back / Home /
 * Recents / notifications via global actions.
 */
class CursorService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var overlay: FrameLayout? = null
    private var pointer: PointerView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val loc = IntArray(2)

    private var x = 0f
    private var y = 0f
    private var w = 1
    private var h = 1
    private var atEdge = false

    // ---- Virtual finger (used for drag and for smooth scrolling) ----
    // One continuous touch built from short "continued" stroke segments.
    private var stroke: GestureDescription.StrokeDescription? = null
    private var fx = 0f
    private var fy = 0f
    private var sentX = 0f
    private var sentY = 0f
    private var busy = false
    private var ending = false
    private var flingX = 0f
    private var flingY = 0f
    private var followCursor = false
    private var fingerDown = false

    /** A drag (finger held down that follows the cursor) is in progress. */
    val isDragging get() = fingerDown && followCursor

    var autoHide = true

    private val hideRunnable = Runnable {
        pointer?.animate()?.alpha(0f)?.setDuration(250)?.start()
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshBounds()
        x = w / 2f; y = h / 2f

        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        val p = PointerView(this, d)
        root.addView(p, FrameLayout.LayoutParams((22 * d).toInt(), (30 * d).toInt()))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        overlay = root
        pointer = p
        wm.addView(root, lp)
        root.post { place() }
    }

    /** Screen size changes when the phone is folded / unfolded. */
    fun refreshBounds() {
        if (!::wm.isInitialized) return
        val b = wm.currentWindowMetrics.bounds
        w = b.width().coerceAtLeast(1); h = b.height().coerceAtLeast(1)
        x = x.coerceIn(0f, (w - 1).toFloat())
        y = y.coerceIn(0f, (h - 1).toFloat())
        overlay?.post { place() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshBounds()
    }

    /** Moves the pointer by GPU translation only (no window relayout) -> smooth. */
    private fun place() {
        val o = overlay ?: return
        val p = pointer ?: return
        o.getLocationOnScreen(loc)
        p.translationX = x - loc[0] - p.tipX
        p.translationY = y - loc[1] - p.tipY
    }

    private fun wake() {
        val p = pointer ?: return
        handler.removeCallbacks(hideRunnable)
        if (p.alpha < 1f) { p.animate().cancel(); p.alpha = 1f }
        if (autoHide && !fingerDown) handler.postDelayed(hideRunnable, 3000)
    }

    fun setCursorVisible(visible: Boolean) {
        pointer?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) wake()
    }

    /** Returns true when the pointer has just hit a screen edge (for a haptic bump). */
    fun moveBy(dx: Float, dy: Float): Boolean {
        val nx = x + dx; val ny = y + dy
        x = nx.coerceIn(0f, (w - 1).toFloat())
        y = ny.coerceIn(0f, (h - 1).toFloat())
        val clamped = x != nx || y != ny
        val bumped = clamped && !atEdge
        atEdge = clamped
        place()
        wake()
        if (isDragging) { fx = x; fy = y; pump() }
        return bumped
    }

    // ---- Clicks ----

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
    }

    fun tap() {
        if (fingerDown) return
        wake()
        dispatch(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 40))
    }

    fun longPress() {
        if (fingerDown) return
        wake()
        dispatch(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 650))
    }

    // ---- Navigation ----

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---- Drag ----

    fun dragStart() {
        if (fingerDown) return
        fingerStart(x, y, 0f, 0f, follow = true)
        pointer?.dragging = true
    }

    fun dragEnd() {
        if (isDragging) fingerEnd(0f, 0f)
    }

    // ---- Scroll: a virtual finger that starts at the pointer and moves like a real swipe ----

    /** dx/dy in inner-screen pixels; same direction as the finger moved. */
    fun scrollBy(dx: Float, dy: Float) {
        if (isDragging || ending) return
        if (!fingerDown) {
            fingerStart(x, y, dx, dy, follow = false)
            return
        }
        val m = SCROLL_MARGIN
        fx = (fx + dx).coerceIn(m, w - m)
        fy = (fy + dy).coerceIn(m, h - m)
        // Ran out of room: lift and the next scrollBy starts again from the pointer.
        if (fx <= m || fx >= w - m || fy <= m || fy >= h - m) fingerEnd(0f, 0f) else pump()
    }

    /** End the scroll; vx/vy (px per ms) let the app fling like a real swipe. */
    fun scrollEnd(vx: Float, vy: Float) {
        if (fingerDown && !followCursor) fingerEnd(vx, vy)
    }

    // ---- Virtual finger internals ----

    private fun fingerStart(sx: Float, sy: Float, dx: Float, dy: Float, follow: Boolean) {
        fingerDown = true
        followCursor = follow
        ending = false
        flingX = 0f; flingY = 0f
        val m = SCROLL_MARGIN
        fx = (sx + dx).coerceIn(m, w - m); fy = (sy + dy).coerceIn(m, h - m)
        val path = Path().apply { moveTo(sx, sy); if (fx != sx || fy != sy) lineTo(fx, fy) }
        sentX = fx; sentY = fy
        val s = GestureDescription.StrokeDescription(path, 0, SEG_MS, true)
        stroke = s
        send(s, last = false)
        handler.removeCallbacks(hideRunnable)
    }

    private fun fingerEnd(vx: Float, vy: Float) {
        if (!fingerDown) return
        ending = true
        flingX = vx; flingY = vy
        pump()
    }

    /** Sends the next segment once the previous one has finished (a new dispatch would cancel it). */
    private fun pump() {
        val prev = stroke ?: return
        if (busy) return
        var tx = fx; var ty = fy
        if (ending && (flingX != 0f || flingY != 0f)) {
            // Last segment continues at release speed so lists fling naturally.
            tx = (fx + flingX * SEG_MS).coerceIn(1f, w - 1f)
            ty = (fy + flingY * SEG_MS).coerceIn(1f, h - 1f)
        }
        val moved = tx != sentX || ty != sentY
        if (!moved && !ending) return
        val path = Path().apply { moveTo(sentX, sentY); if (moved) lineTo(tx, ty) }
        sentX = tx; sentY = ty
        val s = prev.continueStroke(path, 0, SEG_MS, !ending)
        stroke = s
        send(s, last = ending)
    }

    private fun send(s: GestureDescription.StrokeDescription, last: Boolean) {
        busy = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    busy = false
                    if (last) reset() else pump()
                }
                override fun onCancelled(g: GestureDescription?) {
                    busy = false
                    reset()
                }
            },
            null
        )
        if (!ok) { busy = false; reset() }
    }

    private fun reset() {
        fingerDown = false
        followCursor = false
        ending = false
        stroke = null
        pointer?.dragging = false
        wake()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        pointer = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val SEG_MS = 16L
        private const val SCROLL_MARGIN = 8f
        @Volatile var instance: CursorService? = null
    }
}

/** Classic arrow mouse pointer; turns blue while dragging. */
private class PointerView(ctx: Context, private val d: Float) : View(ctx) {
    val tipX = 2 * d
    val tipY = 2 * d

    var dragging = false
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = d
        arrow.reset()
        arrow.moveTo(2 * s, 2 * s)
        arrow.lineTo(2 * s, 24 * s)
        arrow.lineTo(7.5f * s, 18.5f * s)
        arrow.lineTo(11.5f * s, 27 * s)
        arrow.lineTo(15 * s, 25.5f * s)
        arrow.lineTo(11 * s, 17.5f * s)
        arrow.lineTo(18.5f * s, 17.5f * s)
        arrow.close()
    }

    override fun onDraw(c: Canvas) {
        fill.color = if (dragging) Color.rgb(90, 180, 255) else Color.WHITE
        c.drawPath(arrow, fill)
        c.drawPath(arrow, outline)
    }
}
