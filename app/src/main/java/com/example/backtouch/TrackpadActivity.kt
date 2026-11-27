package com.example.backtouch

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Full-screen trackpad on the current screen (for testing without the cover screen). */
class TrackpadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TrackpadView(this))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        closeIfMovedToMainScreen()
    }

    override fun onResume() {
        super.onResume()
        closeIfMovedToMainScreen()
    }

    /** When power mode ends, Android moves this window to the main screen; close it instead. */
    private fun closeIfMovedToMainScreen() {
        val power = intent.getBooleanExtra("power", false)
        if (power && display?.displayId == android.view.Display.DEFAULT_DISPLAY) finish()
    }
}

/**
 * The trackpad surface.
 *
 *  1 finger move          -> move pointer (with acceleration)
 *  tap                    -> click            (haptic: click)
 *  2-finger tap           -> long press       (haptic: double click)
 *  hold (vibrates)        -> then move = drag, or release = long press
 *  2 fingers move         -> smooth scroll, flings on release (haptic: light detents)
 *  3-finger swipe up      -> Home
 *  3-finger swipe left    -> Back
 *  3-finger swipe right   -> Recent apps
 *  3-finger swipe down    -> Notifications
 *  3-finger tap           -> pause / resume
 *  bottom bar             -> Back / Home / Recents buttons
 *
 * Left/right can be mirrored, because a finger on the BACK of the phone moving to the
 * user's right is moving left in the cover screen's own coordinates.
 */
internal class TrackpadView(ctx: Context) : View(ctx) {
    private val prefs = Prefs(ctx)
    private val haptics = Haptics(ctx)
    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    // settings, re-read at the start of every gesture
    private var sens = Prefs.DEF_SENS
    private var accel = Prefs.DEF_ACCEL
    private var feel = true
    private var moveTickAcc = 0f
    private var scrollSpeed = 1.6f
    private var invert = false
    private var mirror = true
    private var navH = 0f

    // gesture state
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var downTime = 0L
    private var moved = 0f
    private var maxPointers = 0
    private var holdArmed = false
    private var ignoring = false
    private var multiDone = false
    private var navFired = false
    private var paused = false
    private var speed = 0f
    private var swipeX = 0f
    private var swipeY = 0f
    private var scrollTickAcc = 0f
    private var scrollVx = 0f
    private var scrollVy = 0f
    private var lastScrollT = 0L
    private var scrolling = false
    private var navPressed = -1

    private val bg = Color.rgb(24, 24, 28)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; textSize = 16 * d; textAlign = Paint.Align.CENTER
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2.5f * d
        strokeJoin = Paint.Join.ROUND
    }
    private val barPaint = Paint().apply { color = Color.rgb(36, 36, 42) }
    private val pressPaint = Paint().apply { color = Color.rgb(60, 60, 70) }

    private val holdTimer = Runnable {
        holdArmed = true
        moved = 0f
        haptics.holdArmed()
    }

    init {
        setBackgroundColor(bg)
        keepScreenOn = true
        loadPrefs()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CursorService.instance?.refreshBounds()
    }

    private fun loadPrefs() {
        sens = prefs.sensitivity
        accel = prefs.acceleration
        scrollSpeed = prefs.scrollSpeed
        invert = prefs.invertScroll
        mirror = prefs.mirrorX
        haptics.level = prefs.haptics
        haptics.style = prefs.hapticStyle
        feel = prefs.movementFeel
        val newNavH = if (prefs.showNavBar) 56 * d else 0f
        if (newNavH != navH) { navH = newNavH; invalidate() }
        CursorService.instance?.autoHide = prefs.autoHideCursor
    }

    // ---- drawing ----

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val padH = height - navH
        val label = if (paused) "Paused - 3-finger tap to resume" else "BackTouch"
        c.drawText(label, width / 2f, padH / 2f, textPaint)
        if (navH <= 0f) return

        c.drawRect(0f, padH, width.toFloat(), height.toFloat(), barPaint)
        val bw = width / 3f
        if (navPressed >= 0) c.drawRect(navPressed * bw, padH, (navPressed + 1) * bw, height.toFloat(), pressPaint)
        val cy = padH + navH / 2
        val s = 9 * d
        // Back: triangle
        val bx = bw * 0.5f
        c.drawLine(bx + s * 0.6f, cy - s, bx - s * 0.6f, cy, iconPaint)
        c.drawLine(bx - s * 0.6f, cy, bx + s * 0.6f, cy + s, iconPaint)
        c.drawLine(bx + s * 0.6f, cy + s, bx + s * 0.6f, cy - s, iconPaint)
        // Home: circle
        c.drawCircle(bw * 1.5f, cy, s, iconPaint)
        // Recents: rounded square
        val rx = bw * 2.5f
        c.drawRoundRect(RectF(rx - s * 0.85f, cy - s * 0.85f, rx + s * 0.85f, cy + s * 0.85f), 3 * d, 3 * d, iconPaint)
    }

    // ---- helpers ----

    private fun isPalm(e: MotionEvent): Boolean {
        for (i in 0 until e.pointerCount) if (e.getTouchMajor(i) > PALM_DP * d) return true
        return false
    }

    private fun inEdge(x: Float, y: Float): Boolean {
        val m = prefs.edgePct / 100f
        val padH = height - navH
        return x < width * m || x > width * (1 - m) || y < padH * m || y > padH - padH * m
    }

    private fun centroid(e: MotionEvent, skip: Int): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        return if (n == 0) lastX to lastY else sx / n to sy / n
    }

    private fun histCentroid(e: MotionEvent, h: Int): Pair<Float, Float> {
        var sx = 0f; var sy = 0f
        for (i in 0 until e.pointerCount) { sx += e.getHistoricalX(i, h); sy += e.getHistoricalY(i, h) }
        return sx / e.pointerCount to sy / e.pointerCount
    }

    private fun navButtonAt(x: Float) = (x / (width / 3f)).toInt().coerceIn(0, 2)

    private fun doNav(i: Int) {
        val svc = CursorService.instance ?: return
        haptics.nav()
        when (i) { 0 -> svc.back(); 1 -> svc.home(); else -> svc.recents() }
    }

    private fun cancelGesture() {
        removeCallbacks(holdTimer)
        ignoring = true
        CursorService.instance?.let { it.dragEnd(); it.scrollEnd(0f, 0f) }
    }

    private fun togglePause() {
        paused = !paused
        CursorService.instance?.setCursorVisible(!paused)
        haptics.toggle()
        invalidate()
    }

    // ---- input ----

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked

        if (action == MotionEvent.ACTION_DOWN) {
            loadPrefs()
            lastX = e.x; lastY = e.y; lastT = e.eventTime
            downTime = e.eventTime; moved = 0f; maxPointers = 1
            holdArmed = false; multiDone = false; navFired = false; scrolling = false
            speed = 0f; swipeX = 0f; swipeY = 0f; scrollTickAcc = 0f
            scrollVx = 0f; scrollVy = 0f
            navPressed = -1
            if (navH > 0f && e.y >= height - navH) {
                if (!paused) { navPressed = navButtonAt(e.x); invalidate() }
                ignoring = paused
                return true
            }
            ignoring = inEdge(e.x, e.y) || isPalm(e)
            if (!ignoring && !paused) postDelayed(holdTimer, HOLD_MS)
            return true
        }

        if (navPressed >= 0) {
            if (action == MotionEvent.ACTION_UP) {
                if (e.y >= height - navH && navButtonAt(e.x) == navPressed) doNav(navPressed)
                navPressed = -1; invalidate()
            } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
                navPressed = -1; invalidate()
            }
            return true
        }

        if (ignoring) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ignoring = false
            return true
        }

        val svc = CursorService.instance
        when (action) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                removeCallbacks(holdTimer)
                if (isPalm(e)) { cancelGesture(); return true }
                if (e.pointerCount >= 3) svc?.scrollEnd(0f, 0f)
                val (x, y) = centroid(e, -1); lastX = x; lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                multiDone = true
                val (x, y) = centroid(e, e.actionIndex); lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPalm(e)) { cancelGesture(); return true }
                // Use every sample the hardware reported, not just one per frame.
                for (h in 0 until e.historySize) {
                    val (hx, hy) = histCentroid(e, h)
                    step(hx, hy, e.getHistoricalEventTime(h), e.pointerCount, svc)
                }
                val (x, y) = centroid(e, -1)
                step(x, y, e.eventTime, e.pointerCount, svc)
            }
            MotionEvent.ACTION_UP -> finish(e, svc)
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdTimer)
                svc?.dragEnd(); svc?.scrollEnd(0f, 0f)
            }
        }
        return true
    }

    private fun step(cx: Float, cy: Float, t: Long, n: Int, svc: CursorService?) {
        var dx = cx - lastX
        val dy = cy - lastY
        val dt = max(1L, t - lastT)
        lastX = cx; lastY = cy; lastT = t
        if (mirror) dx = -dx
        val dist = hypot(dx, dy)
        moved += dist
        if (!holdArmed && moved > slop) removeCallbacks(holdTimer)
        if (paused || svc == null) return

        when {
            maxPointers >= 3 -> {
                swipeX += dx; swipeY += dy
                if (!navFired) {
                    val th = SWIPE_DP * d
                    val action = when {
                        swipeY < -th && abs(swipeY) > abs(swipeX) -> 1      // up: Home
                        swipeY > th && abs(swipeY) > abs(swipeX) -> 3       // down: notifications
                        swipeX < -th -> 0                                   // left: Back
                        swipeX > th -> 2                                    // right: Recents
                        else -> -1
                    }
                    if (action >= 0) {
                        navFired = true
                        haptics.nav()
                        when (action) {
                            0 -> svc.back(); 1 -> svc.home(); 2 -> svc.recents(); else -> svc.notifications()
                        }
                    }
                }
            }
            n >= 2 -> {
                if (!scrolling && moved < slop) return
                scrolling = true
                // From behind the phone it should feel like a laptop wheel: fingers down = page down,
                // the opposite of dragging the screen itself. "Reverse" flips it back.
                val k = scrollSpeed * (if (invert) 1f else -1f)
                val sdx = dx * k; val sdy = dy * k
                svc.scrollBy(sdx, sdy)
                scrollVx = scrollVx * 0.5f + (sdx / dt) * 0.5f
                scrollVy = scrollVy * 0.5f + (sdy / dt) * 0.5f
                lastScrollT = t
                scrollTickAcc += dist
                if (scrollTickAcc > SCROLL_TICK_DP * d) { scrollTickAcc = 0f; haptics.scrollTick() }
            }
            n == 1 && !multiDone -> {
                val inst = dist / dt / d                       // dp per ms
                speed = speed * 0.7f + inst * 0.3f             // smoothed, so it doesn't jitter
                // Pointer ballistics: slow movement is extra precise, normal speed is 1:1 * sens,
                // only fast flicks get accelerated.
                val slow = (speed / 0.6f).coerceIn(0f, 1f)
                val precision = 0.45f + 0.55f * slow * slow * (3 - 2 * slow)   // smoothstep 0.45..1
                val gain = sens * precision + sens * accel * max(0f, min(speed, 3f) - 0.6f)
                if (holdArmed && moved > slop && !svc.isDragging) {
                    svc.dragStart(); haptics.dragStart()
                }
                if (svc.moveBy(dx * gain, dy * gain)) haptics.edge()
                if (feel) {
                    moveTickAcc += dist * gain
                    if (moveTickAcc > MOVE_TICK_DP * d) { moveTickAcc = 0f; haptics.moveTick() }
                }
            }
        }
    }

    private fun finish(e: MotionEvent, svc: CursorService?) {
        removeCallbacks(holdTimer)
        val quick = e.eventTime - downTime < TAP_MS
        val still = moved < slop * maxPointers
        if (maxPointers >= 3) {
            if (!navFired && quick && still) togglePause()
            return
        }
        if (paused || svc == null) return
        when {
            svc.isDragging -> { svc.dragEnd(); haptics.dragEnd() }
            scrolling -> {
                val fresh = e.eventTime - lastScrollT < 60
                svc.scrollEnd(if (fresh) scrollVx else 0f, if (fresh) scrollVy else 0f)
            }
            holdArmed && maxPointers == 1 -> svc.longPress()
            maxPointers == 1 && quick && still -> { svc.tap(); haptics.click() }
            maxPointers == 2 && quick && still -> { svc.longPress(); haptics.rightClick() }
        }
    }

    companion object {
        private const val HOLD_MS = 450L
        private const val TAP_MS = 220L
        private const val PALM_DP = 28f
        private const val SWIPE_DP = 40f
        private const val SCROLL_TICK_DP = 45f
        private const val MOVE_TICK_DP = 70f
    }
}
