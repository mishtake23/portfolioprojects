package com.example.backtouch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Turns the BackTouch accessibility service on by itself. Android only allows this
 * after a one-time grant over ADB (or Shizuku):
 *   adb shell pm grant com.example.backtouch android.permission.WRITE_SECURE_SETTINGS
 */
object AutoEnable {
    const val GRANT_COMMAND =
        "adb shell pm grant com.example.backtouch android.permission.WRITE_SECURE_SETTINGS"

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun enabledList(ctx: Context): List<String> =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }

    private fun isMe(ctx: Context, entry: String): Boolean =
        ComponentName.unflattenFromString(entry) == ComponentName(ctx, CursorService::class.java)

    /** BackTouch is switched on in system settings (it may still be starting). */
    fun isSwitchedOn(ctx: Context) = enabledList(ctx).any { isMe(ctx, it) }

    /**
     * Switches the service on if it is off. Writes nothing when it is already listed:
     * rewriting the setting makes Android restart every accessibility service.
     */
    fun tryEnable(ctx: Context): Boolean {
        if (!hasPermission(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val list = enabledList(ctx)
            if (list.none { isMe(ctx, it) }) {
                val me = ComponentName(ctx, CursorService::class.java).flattenToString()
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (list.distinct() + me).joinToString(":")
                )
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Switches the service off and on again (for when it is listed but not running). */
    fun restart(ctx: Context) {
        if (!hasPermission(ctx)) return
        try {
            val cr = ctx.contentResolver
            val others = enabledList(ctx).filterNot { isMe(ctx, it) }.distinct()
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tryEnable(ctx) }, 600)
        } catch (_: Throwable) { }
    }
}

/** Re-enables the service after a reboot or an app update. */
class AutoEnableReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Prefs(context).coverActive = false
        AutoEnable.tryEnable(context)
    }
}
