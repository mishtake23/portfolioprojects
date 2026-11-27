package com.example.backtouch

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.Display
import rikka.shizuku.Shizuku

/**
 * Optional "power mode" using Shizuku (no root; started once via Wireless debugging).
 * Asks Android, with shell rights, to switch the phone into a state where the cover
 * screen stays on as a real second screen while unfolded, then opens the trackpad there.
 * A trackpad on its own screen is not affected by which app is in front.
 *
 * Which states exist is device specific, so every step is written to a log the user
 * can read (and send to us) from the app.
 */
object PowerMode {
    private const val REQ = 4242

    enum class State { NOT_INSTALLED_OR_STOPPED, NEEDS_PERMISSION, READY }

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.NOT_INSTALLED_OR_STOPPED
            Shizuku.isPreV11() -> State.NOT_INSTALLED_OR_STOPPED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
            else -> State.NEEDS_PERMISSION
        }
    } catch (_: Throwable) {
        State.NOT_INSTALLED_OR_STOPPED
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(REQ) } catch (_: Throwable) { }
    }

    /** Runs a shell command with Shizuku's (ADB-level) rights. Blocking: call off the main thread. */
    fun exec(cmd: String): String = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        (out + err).trim()
    } catch (t: Throwable) {
        "error: ${t.cause?.message ?: t.message ?: t.javaClass.simpleName}"
    }

    /** One-tap fix for "BackTouch got switched off": lets the app re-enable itself. */
    fun grantAutoEnable(ctx: Context): String =
        exec("pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS")

    private data class DevState(val id: Int, val name: String)

    private fun parseStates(out: String): List<DevState> =
        Regex("""identifier=(\d+),\s*name='([^']*)'""").findAll(out)
            .map { DevState(it.groupValues[1].toInt(), it.groupValues[2]) }
            .toList()

    private fun coverDisplay(ctx: Context): Display? {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val rear = runCatching { dm.getDisplays("android.hardware.display.category.REAR") }
            .getOrNull().orEmpty()
        return (rear.toList() + dm.displays.toList())
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
    }

    /** Blocking. Returns a log of what happened. */
    fun start(ctx: Context): String {
        val log = StringBuilder()
        val states = exec("cmd device_state print-states")
        log.appendLine("States:\n$states\n")
        val parsed = parseStates(states)
        val target = parsed.firstOrNull { it.name.contains("CONCURRENT", true) }
            ?: parsed.firstOrNull { it.name.contains("DUAL", true) }
        if (target == null) {
            log.appendLine("No dual-screen state found on this phone, so power mode can't work here.")
            return log.toString()
        }
        Prefs(ctx).powerStateId = target.id
        log.appendLine("Switching to ${target.name} (${target.id})")
        log.appendLine(exec("cmd device_state state ${target.id}").ifBlank { "ok" })
        Thread.sleep(1200)

        val dm = ctx.getSystemService(DisplayManager::class.java)
        log.appendLine("Displays now: " + dm.displays.joinToString { "${it.displayId} '${it.name}' state=${it.state}" })
        val d = coverDisplay(ctx)
        if (d == null) {
            log.appendLine("The cover screen did not appear as a second screen.")
            exec("cmd device_state state reset")
            return log.toString()
        }
        val out = openTrackpad(ctx, d.displayId)
        log.appendLine(out)
        if (out.contains("Error", true) || out.contains("Exception", true)) {
            log.appendLine("Couldn't open the trackpad on the cover screen.")
            exec("cmd device_state state reset")
        } else {
            log.appendLine("Trackpad opened on display ${d.displayId}.")
            Prefs(ctx).powerMode = true
        }
        return log.toString()
    }

    /** Apps aren't allowed to open windows on the cover display themselves; the shell is. */
    private fun openTrackpad(ctx: Context, displayId: Int): String = exec(
        "am start --display $displayId -f 0x18000000 " +
            "-n ${ctx.packageName}/${TrackpadActivity::class.java.name} --ez power true"
    )

    /**
     * Android may switch the cover screen off again (for example when another app comes to
     * the front). Turn it back on and reopen the trackpad. Blocking.
     */
    fun reapply(ctx: Context): Boolean {
        val id = Prefs(ctx).powerStateId
        if (id < 0 || state() != State.READY) return false
        exec("cmd device_state state $id")
        Thread.sleep(800)
        val d = coverDisplay(ctx) ?: return false
        openTrackpad(ctx, d.displayId)
        return true
    }

    /** Blocking. */
    fun stop(ctx: Context): String {
        Prefs(ctx).powerMode = false
        return exec("cmd device_state state reset").ifBlank { "Cover screen back to normal." }
    }
}
