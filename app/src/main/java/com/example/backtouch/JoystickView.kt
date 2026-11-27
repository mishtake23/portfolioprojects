package com.example.backtouch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Small floating joystick on the inner screen (like the "virtual touchpad" on some
 * foldables): push the knob and the pointer glides that way, faster the further you push.
 *   push      -> move pointer
 *   tap       -> click at the pointer
 *   hold      -> pick the joystick up and move it somewhere else
 */
internal class JoystickView(
    ctx: Context,
    private val svc: CursorService,
    private val prefs: Prefs,
) : View(ctx), Choreographer.FrameCallback {

    private val d = resources.displayMetrics.density
    private val sizePx = (88 * d).toInt()
    private val radius = 28 * d
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val haptics = Haptics(ctx)
    private val lp = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var wmRef: WindowManager? = null
    private var knobX = 0f
    private var knobY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var moved = false
    private var moveMode = false
    private var active = false
    private var lastFrameNs = 0L
    private var startLpX = 0
    private var startLpY = 0

    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 30, 30, 35) }
    private val baseEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 250, 250, 250) }
    private val knobShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }

    private val holdTimer = Runnable {
        moveMode = true
        haptics.level = prefs.haptics
        haptics.holdArmed()
        invalidate()
    }

    fun attach(wm: WindowManager) {
        val b = wm.currentWindowMetrics.bounds
        lp.x = prefs.joystickX.takeIf { it >= 0 } ?: (b.width() - sizePx - (24 * d).toInt())
        lp.y = prefs.joystickY.takeIf { it >= 0 } ?: (b.height() * 0.62f).toInt()
        clampToScreen(wm)
        wm.addView(this, lp)
        wmRef = wm
    }

    fun detach(wm: WindowManager) {
        Choreographer.getInstance().removeFrameCallback(this)
        runCatching { wm.removeView(this) }
        wmRef = null
    }

    fun contains(px: Float, py: Float) =
        px >= lp.x && px <= lp.x + sizePx && py >= lp.y && py <= lp.y + sizePx

    /** Briefly ignore touches so a click under the joystick reaches the app below. */
    fun passThrough(wm: WindowManager, ms: Long) {
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(this, lp) }
        postDelayed({
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(this, lp) }
        }, ms)
    }

    private fun clampToScreen(wm: WindowManager) {
        val b = wm.currentWindowMetrics.bounds
        lp.x = lp.x.coerceIn(0, max(0, b.width() - sizePx))
        lp.y = lp.y.coerceIn(0, max(0, b.height() - sizePx))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        base.color = if (moveMode) Color.argb(110, 60, 120, 255) else Color.argb(70, 30, 30, 35)
        c.drawCircle(cx, cy, radius + 10 * d, base)
        c.drawCircle(cx, cy, radius + 10 * d, baseEdge)
        val kr = 15 * d
        c.drawCircle(cx + knobX, cy + knobY + 1.5f * d, kr, knobShadow)
        c.drawCircle(cx + knobX, cy + knobY, kr, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val wm = wmRef ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY; downTime = e.eventTime
                moved = false; moveMode = false; active = true
                startLpX = lp.x; startLpY = lp.y
                haptics.level = prefs.haptics; haptics.style = prefs.hapticStyle
                postDelayed(holdTimer, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX; val dy = e.rawY - downRawY
                if (!moved && hypot(dx, dy) > slop) { moved = true; if (!moveMode) removeCallbacks(holdTimer) }
                if (moveMode) {
                    lp.x = startLpX + dx.toInt(); lp.y = startLpY + dy.toInt()
                    clampToScreen(wm)
                    runCatching { wm.updateViewLayout(this, lp) }
                } else if (moved) {
                    val dist = hypot(dx, dy)
                    val k = if (dist > radius) radius / dist else 1f
                    knobX = dx * k; knobY = dy * k
                    if (lastFrameNs == 0L) {
                        lastFrameNs = System.nanoTime()
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdTimer)
                if (moveMode) {
                    prefs.joystickX = lp.x; prefs.joystickY = lp.y
                } else if (e.actionMasked == MotionEvent.ACTION_UP && !moved &&
                    e.eventTime - downTime < 300
                ) {
                    svc.tap(); haptics.click()
                }
                active = false; moveMode = false
                knobX = 0f; knobY = 0f
                lastFrameNs = 0L
                Choreographer.getInstance().removeFrameCallback(this)
                invalidate()
            }
        }
        return true
    }

    /** Moves the pointer every frame while the knob is pushed. */
    override fun doFrame(frameTimeNanos: Long) {
        if (!active || moveMode) { lastFrameNs = 0L; return }
        val now = System.nanoTime()
        val dt = min(0.05f, (now - lastFrameNs) / 1e9f)
        lastFrameNs = now
        val dist = hypot(knobX, knobY)
        val n = (dist / radius).coerceIn(0f, 1f)
        val dead = 0.12f
        if (n > dead) {
            val t = ((n - dead) / (1 - dead)).pow(1.7f)
            val b = (wmRef ?: return).currentWindowMetrics.bounds
            val top = hypot(b.width().toFloat(), b.height().toFloat()) * 0.9f * prefs.joystickSpeed // px/s
            val v = t * top * dt
            svc.moveBy(knobX / dist * v, knobY / dist * v)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
}
