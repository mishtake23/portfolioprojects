package com.example.backtouch

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup screen: turn the service on, start the cover-screen trackpad, tune settings. */
class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var rear: RearScreen
    private lateinit var prefs: Prefs
    private var rearStatus = "Cover screen: checking..."
    private var rearView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 64)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        rear = RearScreen(this, { msg ->
            rearStatus = msg
            rearView?.text = msg
        }, autoStart = { prefs.autoStartCover && CursorService.instance != null })
        rear.start()
    }

    override fun onResume() {
        super.onResume()
        AutoEnable.tryEnable(this)
        render()
        // The service may take a moment to come up after auto-enable.
        root.postDelayed({ if (!isFinishing) render() }, 800)
    }

    override fun onDestroy() {
        rear.stop()
        super.onDestroy()
    }

    private fun render() {
        root.removeAllViews()
        val running = CursorService.instance != null
        val auto = AutoEnable.hasPermission(this)

        header("Step 1 - Accessibility service")
        text(
            if (running) "ON" else "OFF" +
                if (auto) " (turning on automatically...)" else " - turn it on below"
        )
        if (!running) {
            root.addView(button("Open accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
        if (auto) {
            small("Auto-enable is active: BackTouch switches itself back on after updates and reboots.")
        } else {
            small(
                "Tip: to skip this step forever (also after app updates and reboots), run this " +
                    "once over ADB, or in a Shizuku shell app:\n\n${AutoEnable.GRANT_COMMAND}"
            )
            root.addView(button("Copy command") {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("adb", AutoEnable.GRANT_COMMAND))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            })
        }

        header("Step 2 - Trackpad on the cover screen")
        rearView = text(rearStatus)
        root.addView(button(if (rear.isPresenting) "Stop cover trackpad" else "Start cover trackpad (unfolded)") {
            rear.toggle()
            root.postDelayed({ if (!isFinishing) render() }, 300)
        })
        switch("Start automatically when BackTouch opens unfolded", prefs.autoStartCover) {
            prefs.autoStartCover = it
        }
        root.addView(button("Test trackpad on this screen") {
            startActivity(Intent(this, TrackpadActivity::class.java))
        })

        header("Pointer")
        slider("Speed", 5, 40, (prefs.sensitivity * 10).toInt(), { "%.1f".format(it / 10f) }) {
            prefs.sensitivity = it / 10f
        }
        slider("Acceleration", 0, 20, (prefs.acceleration * 10).toInt(), { "%.1f".format(it / 10f) }) {
            prefs.acceleration = it / 10f
        }
        switch("Mirror left/right (using it from behind)", prefs.mirrorX) { prefs.mirrorX = it }
        switch("Hide pointer when idle", prefs.autoHideCursor) { prefs.autoHideCursor = it }

        header("Scrolling")
        slider("Scroll speed", 5, 40, (prefs.scrollSpeed * 10).toInt(), { "%.1f".format(it / 10f) }) {
            prefs.scrollSpeed = it / 10f
        }
        switch("Invert scroll direction", prefs.invertScroll) { prefs.invertScroll = it }

        header("Feel")
        slider("Vibration", 0, 3, prefs.haptics, { listOf("Off", "Light", "Medium", "Strong")[it] }) {
            prefs.haptics = it
            Haptics(this).apply { level = it }.click()
        }
        slider("Ignore edges (palm rejection)", 0, 15, prefs.edgePct, { "$it%" }) { prefs.edgePct = it }
        switch("Back / Home / Recents bar on the trackpad", prefs.showNavBar) { prefs.showNavBar = it }

        header("Gestures")
        small(
            "Move 1 finger - pointer\n" +
                "Tap - click\n" +
                "Tap twice - double click\n" +
                "Hold (vibrates) then move - drag\n" +
                "Hold and release - long press\n" +
                "2-finger tap - long press (right click)\n" +
                "2 fingers - scroll (flings like a real swipe)\n" +
                "3-finger swipe up - Home\n" +
                "3-finger swipe left - Back\n" +
                "3-finger swipe right - Recent apps\n" +
                "3-finger swipe down - Notifications\n" +
                "3-finger tap - pause / resume"
        )
    }

    // ---- tiny view helpers ----

    private fun header(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 18f; setPadding(0, 48, 0, 8)
    })

    private fun text(t: String) = TextView(this).apply { text = t; textSize = 15f }.also { root.addView(it) }

    private fun small(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 13f; setPadding(0, 12, 0, 12); setTextIsSelectable(true)
    })

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
        root.addView(Switch(this).apply {
            text = label
            isChecked = value
            setPadding(0, 16, 0, 16)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })

    private fun slider(
        label: String, min: Int, max: Int, value: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ) {
        val title = TextView(this).apply { text = "$label: ${fmt(value)}"; setPadding(0, 16, 0, 0) }
        val bar = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    title.text = "$label: ${fmt(v)}"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(title)
        root.addView(bar)
    }
}
