package com.example.backtouch

import android.content.Context

/** Small wrapper around SharedPreferences, shared by the setup screen and the trackpad. */
class Settings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("backtouch", Context.MODE_PRIVATE)

    /** Base cursor speed multiplier. */
    var sensitivity: Float
        get() = p.getFloat("sensitivity", 1.8f)
        set(v) = p.edit().putFloat("sensitivity", v).apply()

    /** Extra speed for fast swipes (0 = linear). */
    var acceleration: Float
        get() = p.getFloat("acceleration", 0.6f)
        set(v) = p.edit().putFloat("acceleration", v).apply()

    /** Touches that START within this % of any edge are ignored (fingers gripping the phone). */
    var edgePct: Int
        get() = p.getInt("edgePct", 6)
        set(v) = p.edit().putInt("edgePct", v).apply()

    var invertScroll: Boolean
        get() = p.getBoolean("invertScroll", false)
        set(v) = p.edit().putBoolean("invertScroll", v).apply()
}
