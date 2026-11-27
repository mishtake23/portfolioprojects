package com.example.backtouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.content.SharedPreferences
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
import kotlin.math.max
import kotlin.math.min

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
    private lateinit var prefs: Prefs
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPrefs() }

    // ---- Auto-restart of the cover trackpad ----
    private var suppressUntil = 0L
    private val recentLaunches = ArrayDeque<Long>()
    private val relaunchRunnable = Runnable { tryRelaunch() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = scheduleRelaunch(600)
    }

    // ---- Power mode watchdog ----
    private val powerLaunches = ArrayDeque<Long>()
    @Volatile private var powerFixing = false
    private val powerCheck = Runnable { checkPowerMode() }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayChanged(id: Int) {}
        override fun onDisplayRemoved(id: Int) {
            if (id == padDisplayId) { padView = null; padWm = null; padDisplayId = -1 }
            handler.removeCallbacks(powerCheck)
            handler.postDelayed(powerCheck, 400)
        }
    }

    private fun checkPowerMode() {
        if (!::prefs.isInitialized || !prefs.powerMode || powerFixing || !isUnfolded()) return
        val dm = getSystemService(DisplayManager::class.java)
        if (padDisplayId != -1 && dm.displays.any { it.displayId == padDisplayId }) return
        val now = SystemClock.uptimeMillis()
        while (powerLaunches.isNotEmpty() && now - powerLaunches.first() > 20_000) powerLaunches.removeFirst()
        if (powerLaunches.size >= 5) {
            prefs.powerMode = false
            Cover.lastError = "Power mode stopped: Android kept turning the cover screen off."
            powerLaunches.clear()
            return
        }
        powerLaunches.addLast(now)
        powerFixing = true
        Thread {
            try { PowerMode.reapply(applicationContext) } finally { powerFixing = false }
        }.start()
    }

    // ---- Power mode: the trackpad as an accessibility overlay on the cover display ----
    private var padView: View? = null
    private var padWm: WindowManager? = null
    var padDisplayId = -1
        private set

    /**
     * Shows the trackpad on [displayId]. Android won't let an app (or even the shell) open an
     * activity on the cover display, but an accessibility service may draw its own overlay there,
     * and that overlay doesn't care which app is in front. Must run on the main thread.
     */
    fun showCoverPad(displayId: Int): String {
        hideCoverPad()
        return try {
            val display = getSystemService(DisplayManager::class.java).getDisplay(displayId)
                ?: return "Display $displayId is gone."
            val ctx = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val pwm = ctx.getSystemService(WindowManager::class.java)
            val view = TrackpadView(ctx)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE
            )
            pwm.addView(view, lp)
            padView = view; padWm = pwm; padDisplayId = displayId
            "Trackpad shown on the cover screen (display $displayId)."
        } catch (t: Throwable) {
            "Couldn't show the trackpad on display $displayId: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun hideCoverPad() {
        padView?.let { v -> runCatching { padWm?.removeView(v) } }
        padView = null; padWm = null; padDisplayId = -1
    }

    /** Called when the cover host closed; brings the trackpad back if the user still wants it. */
    fun scheduleRelaunch(delayMs: Long = 700) {
        handler.removeCallbacks(relaunchRunnable)
        handler.postDelayed(relaunchRunnable, delayMs)
    }

    private fun isUnfolded(): Boolean {
        val b = wm.currentWindowMetrics.bounds
        return min(b.width(), b.height()).toFloat() / max(b.width(), b.height()) > 0.7f
    }

    private fun tryRelaunch() {
        if (!::prefs.isInitialized || !prefs.coverActive || prefs.powerMode) return
        if (Cover.host != null || !isUnfolded()) return
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        if (!pm.isInteractive || km.isKeyguardLocked) return
        val now = SystemClock.uptimeMillis()
        if (now < suppressUntil) { scheduleRelaunch(suppressUntil - now + 100); return }
        // Guard against a restart loop if the cover screen keeps refusing.
        while (recentLaunches.isNotEmpty() && now - recentLaunches.first() > 15_000) recentLaunches.removeFirst()
        if (recentLaunches.size >= 6) {
            prefs.coverActive = false
            Cover.lastError = "Stopped: the cover screen kept closing. Tap Start to try again."
            recentLaunches.clear()
            return
        }
        recentLaunches.addLast(now)
        try { Cover.launchHost(this) } catch (_: Throwable) { }
    }

    private val hideRunnable = Runnable {
        pointer?.animate()?.alpha(0f)?.setDuration(250)?.start()
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshBounds()
        x = w / 2f; y = h / 2f

        prefs = Prefs(this)
        autoHide = prefs.autoHideCursor
        classicScroll = prefs.classicScroll
        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        val p = PointerView(this, d)
        val (pw, ph) = p.sizeFor(pointerScale())
        root.addView(p, FrameLayout.LayoutParams(pw, ph))
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
        prefs.listen(prefListener)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, f)
        scheduleRelaunch(500)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
    }

    private fun pointerScale() = when (prefs.pointerSize) { 0 -> 0.85f; 1 -> 1.1f; else -> 1.4f }

    private fun applyPrefs() {
        autoHide = prefs.autoHideCursor
        classicScroll = prefs.classicScroll
        val p = pointer ?: return
        val (pw, ph) = p.sizeFor(pointerScale())
        p.layoutParams = p.layoutParams.apply { width = pw; height = ph }
        p.post { place() }
        wake()
    }

    /** Screen size changes when the phone is folded / unfolded. */
    fun refreshBounds() {
        if (!::wm.isInitialized) return
        val b = wm.currentWindowMetrics.bounds
        val nw = b.width().coerceAtLeast(1); val nh = b.height().coerceAtLeast(1)
        if (nw != w || nh != h) {
            // Keep the pointer at the same relative spot instead of letting it jump.
            x = x / w * nw; y = y / h * nh
            w = nw; h = nh
        }
        x = x.coerceIn(0f, (w - 1).toFloat())
        y = y.coerceIn(0f, (h - 1).toFloat())
        overlay?.post { place() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshBounds()
        handler.postDelayed(powerCheck, 800) // unfolded again: power mode back on
        scheduleRelaunch(800) // unfolded again: bring the trackpad back
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
    fun recents(): Boolean {
        // Restarting the trackpad would close the Recents screen, so hold off for a while.
        suppressUntil = SystemClock.uptimeMillis() + 8000
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
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
        if (classicScroll) { classicScrollBy(dx, dy); return }
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
        if (classicScroll) { classicPendX = 0f; classicPendY = 0f; return }
        if (fingerDown && !followCursor) fingerEnd(vx, vy)
    }

    // ---- Classic scroll: separate short swipes, queued so they never cancel each other ----

    var classicScroll = false
    private var classicPendX = 0f
    private var classicPendY = 0f
    private var classicBusy = false

    private fun classicScrollBy(dx: Float, dy: Float) {
        if (fingerDown) return
        classicPendX += dx; classicPendY += dy
        pumpClassic()
    }

    private fun pumpClassic() {
        if (classicBusy) return
        val minPx = 24 * resources.displayMetrics.density
        if (Math.hypot(classicPendX.toDouble(), classicPendY.toDouble()) < minPx) return
        val m = SCROLL_MARGIN
        val sx = r(x.coerceIn(m, w - m)); val sy = r(y.coerceIn(m, h - m))
        val ex = r((sx + classicPendX).coerceIn(m, w - m)); val ey = r((sy + classicPendY).coerceIn(m, h - m))
        classicPendX = 0f; classicPendY = 0f
        if (ex == sx && ey == sy) return
        classicBusy = true
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 120)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { classicBusy = false; pumpClassic() }
                override fun onCancelled(g: GestureDescription?) { classicBusy = false; pumpClassic() }
            },
            null
        )
        if (!ok) classicBusy = false
    }

    // ---- Virtual finger internals ----

    private fun fingerStart(sx: Float, sy: Float, dx: Float, dy: Float, follow: Boolean) {
        fingerDown = true
        followCursor = follow
        ending = false
        flingX = 0f; flingY = 0f
        val m = SCROLL_MARGIN
        // Whole pixels only: Android drops a continued touch whose next piece doesn't start
        // exactly where the last one ended, and fractions make that fail at random.
        val ox = r(sx); val oy = r(sy)
        fx = (sx + dx).coerceIn(m, w - m); fy = (sy + dy).coerceIn(m, h - m)
        val ex = r(fx); val ey = r(fy)
        val path = Path().apply { moveTo(ox, oy); if (ex != ox || ey != oy) lineTo(ex, ey) }
        sentX = ex; sentY = ey
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
        var tx = r(fx); var ty = r(fy)
        if (ending && (flingX != 0f || flingY != 0f)) {
            // Last segment continues at release speed so lists fling naturally.
            tx = r((fx + flingX * SEG_MS).coerceIn(1f, w - 1f))
            ty = r((fy + flingY * SEG_MS).coerceIn(1f, h - 1f))
        }
        val moved = tx != sentX || ty != sentY
        if (!moved && !ending) return
        val path = Path().apply { moveTo(sentX, sentY); if (moved) lineTo(tx, ty) }
        sentX = tx; sentY = ty
        val s = prev.continueStroke(path, 0, SEG_MS, !ending)
        stroke = s
        send(s, last = ending)
    }

    private fun r(v: Float) = Math.round(v).toFloat()

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
        if (::prefs.isInitialized) prefs.unlisten(prefListener)
        hideCoverPad()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
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

/** Small white arrow with a dark outline and soft shadow, like the Samsung DeX pointer. */
private class PointerView(ctx: Context, private val d: Float) : View(ctx) {
    private var s = d * 0.85f
    val tipX get() = PAD * s
    val tipY get() = PAD * s

    var dragging = false
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 20, 20)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val arrow = Path()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // needed for the shadow

    /** Sets the scale and returns the view size in pixels. */
    fun sizeFor(scale: Float): Pair<Int, Int> {
        s = d * scale
        return ((W + 2 * PAD) * s).toInt() to ((H + 2 * PAD) * s).toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // Arrow outline in "pointer units" (tip at 0,0), offset by PAD for outline + shadow.
        val pts = floatArrayOf(
            0f, 0f,
            0f, 16.5f,
            3.9f, 12.9f,
            6.4f, 18.6f,
            8.9f, 17.5f,
            6.4f, 11.9f,
            11.6f, 11.9f
        )
        arrow.reset()
        for (i in pts.indices step 2) {
            val px = (pts[i] + PAD) * s; val py = (pts[i + 1] + PAD) * s
            if (i == 0) arrow.moveTo(px, py) else arrow.lineTo(px, py)
        }
        arrow.close()
        outline.strokeWidth = 1.1f * s
        fill.setShadowLayer(1.6f * s, 0f, 0.8f * s, Color.argb(110, 0, 0, 0))
    }

    override fun onDraw(c: Canvas) {
        fill.color = if (dragging) Color.rgb(120, 190, 255) else Color.WHITE
        c.drawPath(arrow, fill)
        c.drawPath(arrow, outline)
    }

    companion object {
        private const val W = 12f
        private const val H = 19f
        private const val PAD = 3f
    }
}
