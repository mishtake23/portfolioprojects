package com.example.backtouch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Setup + settings screen. */
class MainActivity : ComponentActivity() {
    private lateinit var rear: RearScreen
    private lateinit var prefs: Prefs
    private val rearStatus = mutableStateOf("Checking the cover screen...")
    private val available = mutableStateOf(false)
    private val running = mutableStateOf(false)
    private val active = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val powerLog = mutableStateOf("")
    private val powerBusy = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // Only used here to know whether the cover screen can be used right now.
        rear = RearScreen(this, object : RearScreen.Listener {
            override fun onAvailability(available: Boolean, message: String) {
                this@MainActivity.available.value = available
                rearStatus.value = message
                if (available && prefs.autoStartCover && !prefs.coverActive && !prefs.powerMode &&
                    CursorService.instance != null
                ) Cover.start(this@MainActivity)
            }
        })
        rear.start()
        setContent { BackTouchTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        if (CursorService.instance == null) AutoEnable.tryEnable(this)
    }

    override fun onDestroy() {
        rear.stop()
        super.onDestroy()
    }

    // ---- intents ----

    /** Opens BackTouch's own switch where possible, otherwise the accessibility list. */
    private fun openAccessibility() {
        val comp = ComponentName(this, CursorService::class.java).flattenToString()
        val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, comp)
        val highlighted = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", comp)
            .putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", comp)
            })
        for (i in listOf(direct, highlighted)) {
            try { startActivity(i); return } catch (_: Exception) { }
        }
    }

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun copyGrantCommand() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("adb", AutoEnable.GRANT_COMMAND))
        Toast.makeText(this, "Command copied", Toast.LENGTH_SHORT).show()
    }

    // ---- UI ----

    @Composable
    private fun Screen() {
        var serviceOn by remember { mutableStateOf(CursorService.instance != null) }
        LaunchedEffect(Unit) {
            while (true) {
                serviceOn = CursorService.instance != null
                running.value = Cover.running
                active.value = prefs.coverActive || prefs.powerMode
                error.value = Cover.lastError
                delay(400)
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.systemBars)   // keep content out from under the status bar
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally)   // centred on the big inner screen
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) {
                    Text("BackTouch", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Use your cover screen as a trackpad",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!serviceOn) SetupCard() else TrackpadCard()
                SettingsSections()   // always visible, even before setup
                GesturesCard()
                AdvancedCard(serviceOn)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun SetupCard() {
        var helpOpen by remember { mutableStateOf(false) }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(28.dp)
        ) {
            if (AutoEnable.hasPermission(this@MainActivity)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("BackTouch is starting...", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "It's switched on in settings but hasn't started yet. If this takes more than " +
                            "a few seconds, restart it.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { AutoEnable.restart(this@MainActivity) },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("Restart BackTouch", style = MaterialTheme.typography.titleMedium) }
                }
                return@Card
            }
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("One quick setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "To move a pointer and tap for you, BackTouch needs to be switched on in " +
                        "Accessibility. You only do this once.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Step(1, "Tap Turn on below")
                Step(2, "Switch BackTouch on and tap Allow")
                Step(3, "Come back here - you're done")
                Button(
                    onClick = { openAccessibility() },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Turn on", style = MaterialTheme.typography.titleMedium) }

                Row(
                    Modifier.fillMaxWidth().clickable { helpOpen = !helpOpen }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Switch is greyed out?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Icon(if (helpOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                }
                AnimatedVisibility(helpOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Android does this for apps installed outside the Play Store. " +
                                "Open App info, tap the ⋮ menu in the top corner, choose " +
                                "\"Allow restricted settings\", then tap Turn on again.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(onClick = { openAppInfo() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Open App info")
                        }
                    }
                }

                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Info, null, Modifier.size(18.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "BackTouch can't read what's on your screen. It only taps and swipes where you point.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    @Composable
    private fun Step(n: Int, text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).then(Modifier),
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }

    @Composable
    private fun TrackpadCard() {
        val on = active.value
        val showing = running.value
        var auto by remember { mutableStateOf(prefs.autoStartCover) }
        val status = when {
            prefs.powerMode -> "Power mode is on"
            showing -> "Running on the cover screen"
            on && !available.value -> "Paused - unfold your phone and it comes back"
            on -> "Starting..."
            else -> rearStatus.value
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Trackpad", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                error.value?.let {
                    if (!showing) Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        if (on) {
                            Cover.stop(this@MainActivity)
                            if (prefs.powerMode) runPower(start = false)
                        } else {
                            Cover.start(this@MainActivity)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(if (on) Icons.Filled.Close else Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (on) "Stop" else "Start on cover screen", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Stays on while you use other apps. The cover screen may blink for a moment " +
                        "when you switch apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                SwitchRow("Start automatically", "When you open BackTouch unfolded", auto) {
                    auto = it; prefs.autoStartCover = it
                }
            }
        }
    }

    /** Runs power mode start/stop off the main thread and shows its log. */
    private fun runPower(start: Boolean) {
        powerBusy.value = true
        Thread {
            val log = if (start) {
                Cover.stop(this) // auto-restart mode off while power mode runs
                PowerMode.start(applicationContext)
            } else {
                PowerMode.stop(applicationContext)
            }
            runOnUiThread { powerLog.value = log; powerBusy.value = false }
        }.start()
    }

    private fun grantViaShizuku() {
        powerBusy.value = true
        Thread {
            val out = PowerMode.grantAutoEnable(applicationContext)
            val ok = AutoEnable.tryEnable(applicationContext)
            runOnUiThread {
                powerLog.value = if (ok) "Done. BackTouch now switches itself back on." else "Result: $out"
                powerBusy.value = false
            }
        }.start()
    }

    @Composable
    private fun SettingsSections() {
        var size by remember { mutableStateOf(prefs.pointerSize) }
        var speed by remember { mutableStateOf(prefs.sensitivity) }
        var accel by remember { mutableStateOf(prefs.acceleration) }
        var mirror by remember { mutableStateOf(prefs.mirrorX) }
        var hide by remember { mutableStateOf(prefs.autoHideCursor) }
        var scroll by remember { mutableStateOf(prefs.scrollSpeed) }
        var invert by remember { mutableStateOf(prefs.invertScroll) }
        var classic by remember { mutableStateOf(prefs.classicScroll) }
        var vib by remember { mutableStateOf(prefs.haptics) }
        var edge by remember { mutableStateOf(prefs.edgePct.toFloat()) }
        var nav by remember { mutableStateOf(prefs.showNavBar) }
        var style by remember { mutableStateOf(prefs.pointerStyle) }
        var color by remember { mutableStateOf(prefs.pointerColor) }
        var opacity by remember { mutableStateOf(prefs.pointerOpacity.toFloat()) }
        var joy by remember { mutableStateOf(prefs.joystick) }
        var joySpeed by remember { mutableStateOf(prefs.joystickSpeed) }
        val ctx = LocalContext.current

        Section("Pointer") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                PointerPreview(style, color, opacity / 100f, size)
                Spacer(Modifier.width(16.dp))
                Text(
                    "Looks like this on the inner screen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Segmented("Style", listOf("Dot", "Arrow", "Ring"), style) { style = it; prefs.pointerStyle = it }
            Text("Colour", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            ColorSwatches(color) { color = it; prefs.pointerColor = it }
            Segmented("Size", listOf("Small", "Medium", "Large"), size) { size = it; prefs.pointerSize = it }
            SliderRow("Opacity", opacity, 30f..100f, "${opacity.toInt()}%") { opacity = it; prefs.pointerOpacity = it.toInt() }
            SliderRow("Speed", speed, 0.5f..4f, "%.1f".format(speed)) { speed = it; prefs.sensitivity = it }
            SliderRow("Acceleration", accel, 0f..2f, "%.1f".format(accel)) { accel = it; prefs.acceleration = it }
            SwitchRow("Mirror left and right", "Keep on when your fingers are behind the phone", mirror) {
                mirror = it; prefs.mirrorX = it
            }
            SwitchRow("Hide when idle", null, hide) { hide = it; prefs.autoHideCursor = it }
        }
        Section("Joystick") {
            SwitchRow(
                "Floating joystick",
                "A small joystick on the inner screen: push it to move the pointer, tap it to click, hold it to move it",
                joy
            ) { joy = it; prefs.joystick = it }
            if (joy) {
                SliderRow("Joystick speed", joySpeed, 0.3f..2.5f, "%.1f".format(joySpeed)) {
                    joySpeed = it; prefs.joystickSpeed = it
                }
            }
        }
        Section("Scrolling") {
            SliderRow("Scroll speed", scroll, 0.5f..4f, "%.1f".format(scroll)) { scroll = it; prefs.scrollSpeed = it }
            SwitchRow("Reverse direction", null, invert) { invert = it; prefs.invertScroll = it }
            SwitchRow("Classic scrolling", "Short swipes instead of a continuous finger. Try this if scrolling stops or skips", classic) {
                classic = it; prefs.classicScroll = it
            }
        }
        Section("Feel") {
            Segmented("Vibration", listOf("Off", "Light", "Medium", "Strong"), vib) {
                vib = it; prefs.haptics = it
                Haptics(ctx).apply { level = it }.click()
            }
            SliderRow("Ignore edges", edge, 0f..15f, "${edge.toInt()}%", steps = 14) {
                edge = it; prefs.edgePct = it.toInt()
            }
            SwitchRow("Back, Home and Recents buttons", "Shown along the bottom of the trackpad", nav) {
                nav = it; prefs.showNavBar = it
            }
        }
    }

    @Composable
    private fun GesturesCard() {
        Section("Gestures") {
            val rows = listOf(
                "Slide 1 finger" to "Move pointer",
                "Tap" to "Click",
                "Tap twice" to "Double click",
                "2-finger tap" to "Right click",
                "Hold until it buzzes" to "Long press",
                "Hold, then slide" to "Drag",
                "Slide 2 fingers" to "Scroll",
                "3 fingers up" to "Home",
                "3 fingers left" to "Back",
                "3 fingers right" to "Recent apps",
                "3 fingers down" to "Notifications",
                "3-finger tap" to "Pause / resume",
                "Joystick: push" to "Move pointer",
                "Joystick: tap" to "Click",
                "Joystick: hold" to "Move the joystick",
            )
            rows.forEachIndexed { i, (g, a) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(g, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(a, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (i < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    @Composable
    private fun AdvancedCard(serviceOn: Boolean) {
        var open by remember { mutableStateOf(false) }
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                }
                AnimatedVisibility(open) {
                    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (serviceOn) {
                            OutlinedButton(
                                onClick = { startActivity(Intent(this@MainActivity, TrackpadActivity::class.java)) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Try the trackpad on this screen") }
                        }
                        PowerModeSection()
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Keep BackTouch switched on", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (AutoEnable.hasPermission(this@MainActivity))
                                "Active. BackTouch switches itself back on after updates and restarts."
                            else
                                "Optional, for developers. If Android ever switches BackTouch off " +
                                    "(for example after an update), this one-time ADB command lets it " +
                                    "switch itself back on:\n\n${AutoEnable.GRANT_COMMAND}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!AutoEnable.hasPermission(this@MainActivity)) {
                            TextButton(onClick = { copyGrantCommand() }) { Text("Copy command") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PowerModeSection() {
        var shizuku by remember { mutableStateOf(PowerMode.state()) }
        LaunchedEffect(Unit) {
            while (true) { shizuku = PowerMode.state(); delay(1000) }
        }
        Text("Power mode (Shizuku) - experimental", style = MaterialTheme.typography.titleSmall)
        Text(
            "Turns the cover screen on as a real second screen, so the trackpad never " +
                "blinks when you switch apps. Needs the free Shizuku app (no root), started " +
                "once with Wireless debugging. It may not work on every phone; the log below " +
                "shows what happened.",
            style = MaterialTheme.typography.bodySmall
        )
        when (shizuku) {
            PowerMode.State.NOT_INSTALLED_OR_STOPPED -> {
                Text("Shizuku isn't installed or isn't running.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                OutlinedButton(
                    onClick = {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Get Shizuku") }
            }
            PowerMode.State.NEEDS_PERMISSION -> {
                Button(onClick = { PowerMode.requestPermission() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow BackTouch in Shizuku")
                }
            }
            PowerMode.State.READY -> {
                val busy = powerBusy.value
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { runPower(start = !prefs.powerMode) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (prefs.powerMode) "Stop power mode" else "Start power mode") }
                    OutlinedButton(onClick = { grantViaShizuku() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Keep switched on", maxLines = 1)
                    }
                }
            }
        }
        if (powerBusy.value) Text("Working...", style = MaterialTheme.typography.bodySmall)
        if (powerLog.value.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp)
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        powerLog.value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun PointerPreview(style: Int, argb: Int, alpha: Float, size: Int) {
        val scale = when (size) { 0 -> 0.85f; 1 -> 1.1f; else -> 1.4f } * 1.6f
        val main = androidx.compose.ui.graphics.Color(argb).copy(alpha = alpha)
        val edge = if (androidx.compose.ui.graphics.Color(argb).luminance() > 0.5f)
            androidx.compose.ui.graphics.Color(0xFF191919).copy(alpha = alpha)
        else androidx.compose.ui.graphics.Color.White.copy(alpha = alpha)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(64.dp)
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val u = 1.dp.toPx() * scale
                val c = center
                when (style) {
                    1 -> {
                        val pts = listOf(0f to 0f, 0f to 16.5f, 3.9f to 12.9f, 6.4f to 18.6f, 8.9f to 17.5f, 6.4f to 11.9f, 11.6f to 11.9f)
                        val ox = c.x - 6f * u; val oy = c.y - 9.5f * u
                        val path = androidx.compose.ui.graphics.Path().apply {
                            pts.forEachIndexed { i, (px, py) ->
                                if (i == 0) moveTo(ox + px * u, oy + py * u) else lineTo(ox + px * u, oy + py * u)
                            }
                            close()
                        }
                        drawPath(path, main)
                        drawPath(path, edge, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.1f * u))
                    }
                    2 -> {
                        val r = 9f * u - 1.5f * u
                        drawCircle(edge, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.2f * u))
                        drawCircle(main, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4f * u))
                        drawCircle(main, 1.4f * u, c)
                    }
                    else -> {
                        drawCircle(main, 5.5f * u, c)
                        drawCircle(edge, 5.5f * u, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f * u))
                    }
                }
            }
        }
    }

    @Composable
    private fun ColorSwatches(selected: Int, onPick: (Int) -> Unit) {
        val colors = listOf(
            0xFFFFFFFF, 0xFF1C1C1E, 0xFF3D7BFF, 0xFFFF4D4D,
            0xFF2ECC71, 0xFFFFD43B, 0xFF9B5CFF, 0xFFFF8A3D
        ).map { it.toInt() }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            colors.forEach { c ->
                val isSel = c == selected
                Box(
                    Modifier
                        .size(36.dp)
                        .border(
                            width = if (isSel) 3.dp else 1.dp,
                            color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                        .padding(4.dp)
                        .background(androidx.compose.ui.graphics.Color(c), CircleShape)
                        .clickable { onPick(c) }
                )
            }
        }
    }

    // ---- building blocks ----

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                content()
            }
        }
    }

    @Composable
    private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun SliderRow(
        title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String,
        steps: Int = 0, onChange: (Float) -> Unit
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            Row {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Segmented(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = i == selected,
                        onClick = { onSelect(i) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size)
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun BackTouchTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
