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

    /** Returns true if the service is (now) switched on in system settings. */
    fun tryEnable(ctx: Context): Boolean {
        if (!hasPermission(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val me = ComponentName(ctx, CursorService::class.java).flattenToString()
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val list = current.split(':').filter { it.isNotBlank() }
            if (list.none { it.equals(me, ignoreCase = true) }) {
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (list + me).joinToString(":")
                )
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (t: Throwable) {
            false
        }
    }
}

/** Re-enables the service after a reboot or an app update. */
class AutoEnableReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Prefs(context).coverActive = false
        AutoEnable.tryEnable(context)
    }
}
