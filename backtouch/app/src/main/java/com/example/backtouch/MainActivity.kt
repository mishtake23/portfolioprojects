package com.example.backtouch

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup + diagnostics: lists displays and launches the trackpad on a chosen one. */
class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        root.addView(button("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays
        val sb = StringBuilder()
        sb.append("Accessibility service: ")
            .append(if (CursorService.instance != null) "ON" else "OFF").append("\n\nDisplays:\n")
        for (d in displays) {
            sb.append("  id=${d.displayId}  \"${d.name}\"  state=${d.state}  ${d.width}x${d.height}\n")
        }
        status.text = sb.toString()

        root.addView(button("Test trackpad on THIS screen") {
            startActivity(Intent(this, TrackpadActivity::class.java))
        })
        for (d in displays.filter { it.displayId != android.view.Display.DEFAULT_DISPLAY }) {
            root.addView(button("2. Open trackpad on display ${d.displayId} (${d.name})") {
                launchOn(d.displayId)
            })
        }
        root.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, 32, 0, 0)
            text = "Unfold the phone, then press the display button. If the cover " +
                "display is off, the launch will fail or show nothing - that tells us " +
                "we need the ADB/Shizuku route to wake it."
        })
    }

    private fun launchOn(displayId: Int) {
        try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            startActivity(
                Intent(this, TrackpadActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                opts.toBundle()
            )
        } catch (t: Throwable) {
            Toast.makeText(this, "Launch failed: ${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }
}
