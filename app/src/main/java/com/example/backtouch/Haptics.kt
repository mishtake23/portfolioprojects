package com.example.backtouch

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Distinct vibration for each trackpad action. Uses the crisp "primitive" effects
 * where the phone supports them (Samsung flagships do), with a plain buzz fallback.
 */
class Haptics(ctx: Context) {
    private val vib: Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }

    /** 0 = off, 1 = light, 2 = medium, 3 = strong. */
    var level = 2

    private val mult get() = when (level) { 1 -> 0.45f; 2 -> 0.75f; else -> 1f }

    /** Plays primitives as (id, scale, delayMs); returns false if unsupported. */
    private fun compose(vararg parts: Triple<Int, Float, Int>): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val ids = parts.map { it.first }.distinct().toIntArray()
        if (!vib.areAllPrimitivesSupported(*ids)) return false
        val c = VibrationEffect.startComposition()
        for ((id, scale, delay) in parts) c.addPrimitive(id, (scale * mult).coerceIn(0.05f, 1f), delay)
        vib.vibrate(c.compose())
        return true
    }

    private fun buzz(ms: Long, amp: Int) {
        val a = (amp * mult).toInt().coerceIn(1, 255)
        vib.vibrate(VibrationEffect.createOneShot(ms, if (vib.hasAmplitudeControl()) a else VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun play(fallbackMs: Long, fallbackAmp: Int, vararg parts: Triple<Int, Float, Int>) {
        if (level <= 0) return
        if (!compose(*parts)) buzz(fallbackMs, fallbackAmp)
    }

    private val CLICK = VibrationEffect.Composition.PRIMITIVE_CLICK
    private val TICK = VibrationEffect.Composition.PRIMITIVE_TICK
    private val LOW_TICK get() = if (Build.VERSION.SDK_INT >= 31) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else TICK
    private val THUD get() = if (Build.VERSION.SDK_INT >= 31) VibrationEffect.Composition.PRIMITIVE_THUD else CLICK
    private val RISE = VibrationEffect.Composition.PRIMITIVE_QUICK_RISE

    /** Left click: one crisp click. */
    fun click() = play(12, 200, Triple(CLICK, 1f, 0))

    /** Right click (two-finger tap): two quick clicks. */
    fun rightClick() = play(30, 200, Triple(CLICK, 0.9f, 0), Triple(CLICK, 0.9f, 70))

    /** Hold registered (ready to drag / long press): a heavy thud you can clearly feel. */
    fun holdArmed() = play(45, 255, Triple(THUD, 1f, 0), Triple(CLICK, 0.8f, 30))

    /** Drag picked up / dropped. */
    fun dragStart() = play(15, 160, Triple(TICK, 0.9f, 0))
    fun dragEnd() = play(15, 180, Triple(CLICK, 0.7f, 0))

    /** Light detent while scrolling. */
    fun scrollTick() = play(5, 60, Triple(LOW_TICK, 0.35f, 0))

    /** Cursor bumped into the screen edge. */
    fun edge() = play(8, 90, Triple(TICK, 0.5f, 0))

    /** Back / Home / Recents / notifications. */
    fun nav() = play(25, 220, Triple(RISE, 0.8f, 0), Triple(CLICK, 0.8f, 0))

    /** Pause / resume. */
    fun toggle() = play(40, 200, Triple(TICK, 1f, 0), Triple(TICK, 1f, 90), Triple(TICK, 1f, 90))
}
