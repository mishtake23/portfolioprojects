package com.example.backtouch

import android.content.Context
import android.content.SharedPreferences

/** User settings, shared by the setup screen, the trackpad and the cursor service. */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("backtouch", Context.MODE_PRIVATE)

    private fun f(key: String, def: Float) = p.getFloat(key, def)
    private fun putF(key: String, v: Float) = p.edit().putFloat(key, v).apply()
    private fun b(key: String, def: Boolean) = p.getBoolean(key, def)
    private fun putB(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()

    /** Base cursor speed multiplier. */
    var sensitivity: Float get() = f("sensitivity", DEF_SENS); set(v) = putF("sensitivity", v)

    /** Extra speed for fast swipes (0 = linear). */
    var acceleration: Float get() = f("acceleration", DEF_ACCEL); set(v) = putF("acceleration", v)

    /** How far content scrolls per finger movement. */
    var scrollSpeed: Float get() = f("scrollSpeed", 1.6f); set(v) = putF("scrollSpeed", v)

    /** Touches that START within this % of any edge are ignored (fingers gripping the phone). */
    var edgePct: Int get() = p.getInt("edgePct", 6); set(v) = p.edit().putInt("edgePct", v).apply()

    /** Flip left/right, because the cover screen faces away from you when used from behind. */
    var mirrorX: Boolean get() = b("mirrorX", true); set(v) = putB("mirrorX", v)

    /** Scroll with separate short swipes instead of one continuous finger (fallback). */
    var classicScroll: Boolean get() = b("classicScroll", false); set(v) = putB("classicScroll", v)

    var invertScroll: Boolean get() = b("invertScroll", false); set(v) = putB("invertScroll", v)

    /** 0 = off, 1 = light, 2 = medium, 3 = strong. */
    var haptics: Int get() = p.getInt("haptics", 2); set(v) = p.edit().putInt("haptics", v).apply()

    /** Back / Home / Recents buttons along the bottom of the trackpad. */
    var showNavBar: Boolean get() = b("showNavBar", true); set(v) = putB("showNavBar", v)

    /** 0 = small (default, DeX-like), 1 = medium, 2 = large. */
    var pointerSize: Int get() = p.getInt("pointerSize", 0); set(v) = p.edit().putInt("pointerSize", v).apply()

    /** 0 = dot (default), 1 = arrow, 2 = ring. */
    var pointerStyle: Int get() = p.getInt("pointerStyle", 0); set(v) = p.edit().putInt("pointerStyle", v).apply()

    /** ARGB colour of the pointer. */
    var pointerColor: Int get() = p.getInt("pointerColor", 0xFFFFFFFF.toInt()); set(v) = p.edit().putInt("pointerColor", v).apply()

    /** Pointer opacity in percent (30..100). */
    var pointerOpacity: Int get() = p.getInt("pointerOpacity", 90); set(v) = p.edit().putInt("pointerOpacity", v).apply()

    /** Floating joystick on the inner screen. */
    var joystick: Boolean get() = b("joystick", false); set(v) = putB("joystick", v)
    var joystickX: Int get() = p.getInt("joystickX", -1); set(v) = p.edit().putInt("joystickX", v).apply()
    var joystickY: Int get() = p.getInt("joystickY", -1); set(v) = p.edit().putInt("joystickY", v).apply()

    /** Joystick top speed multiplier. */
    var joystickSpeed: Float get() = f("joystickSpeed", 1f); set(v) = putF("joystickSpeed", v)

    /** 0 = crisp, 1 = soft, 2 = punchy. */
    var hapticStyle: Int get() = p.getInt("hapticStyle", 0); set(v) = p.edit().putInt("hapticStyle", v).apply()

    /** Faint ticks while the pointer moves, like feeling a surface. */
    var movementFeel: Boolean get() = b("movementFeel", true); set(v) = putB("movementFeel", v)

    fun resetPointer() {
        sensitivity = DEF_SENS; acceleration = DEF_ACCEL
    }

    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        p.registerOnSharedPreferenceChangeListener(l)

    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        p.unregisterOnSharedPreferenceChangeListener(l)

    /** Fade the cursor out after a few seconds without movement. */
    var autoHideCursor: Boolean get() = b("autoHideCursor", true); set(v) = putB("autoHideCursor", v)

    /** The user has switched the cover trackpad on (auto-restart keeps it alive). */
    var coverActive: Boolean get() = b("coverActive", false); set(v) = putB("coverActive", v)

    /** Use Shizuku to turn the cover screen on as a real second screen. */
    var powerMode: Boolean get() = b("powerMode", false); set(v) = putB("powerMode", v)

    /** Device state id used by power mode (-1 = unknown). */
    var powerStateId: Int get() = p.getInt("powerStateId", -1); set(v) = p.edit().putInt("powerStateId", v).apply()

    /** Put the trackpad on the cover screen as soon as the app is opened unfolded. */
    var autoStartCover: Boolean get() = b("autoStartCover", false); set(v) = putB("autoStartCover", v)

    companion object {
        const val DEF_SENS = 1.0f
        const val DEF_ACCEL = 0.5f
    }
}
