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
import androidx.compose.foundation.clickable
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
    private val presenting = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        rear = RearScreen(this, { msg ->
            rearStatus.value = msg
            presenting.value = rear.isPresenting
        }, autoStart = { prefs.autoStartCover && CursorService.instance != null })
        rear.start()
        setContent { BackTouchTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        AutoEnable.tryEnable(this)
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
                presenting.value = rear.isPresenting
                delay(500)
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.systemBars)
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

                if (!serviceOn) {
                    SetupCard()
                } else {
                    TrackpadCard()
                    SettingsSections()
                    GesturesCard()
                }
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
        val running = presenting.value
        var auto by remember { mutableStateOf(prefs.autoStartCover) }
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
                Text(rearStatus.value, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { rear.toggle() },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(if (running) Icons.Filled.Close else Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Stop" else "Start on cover screen", style = MaterialTheme.typography.titleMedium)
                }
                SwitchRow("Start automatically", "When you open BackTouch unfolded", auto) {
                    auto = it; prefs.autoStartCover = it
                }
            }
        }
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
        var vib by remember { mutableStateOf(prefs.haptics) }
        var edge by remember { mutableStateOf(prefs.edgePct.toFloat()) }
        var nav by remember { mutableStateOf(prefs.showNavBar) }
        val ctx = LocalContext.current

        Section("Pointer") {
            Segmented("Size", listOf("Small", "Medium", "Large"), size) { size = it; prefs.pointerSize = it }
            SliderRow("Speed", speed, 0.5f..4f, "%.1f".format(speed)) { speed = it; prefs.sensitivity = it }
            SliderRow("Acceleration", accel, 0f..2f, "%.1f".format(accel)) { accel = it; prefs.acceleration = it }
            SwitchRow("Mirror left and right", "Keep on when your fingers are behind the phone", mirror) {
                mirror = it; prefs.mirrorX = it
            }
            SwitchRow("Hide when idle", null, hide) { hide = it; prefs.autoHideCursor = it }
        }
        Section("Scrolling") {
            SliderRow("Scroll speed", scroll, 0.5f..4f, "%.1f".format(scroll)) { scroll = it; prefs.scrollSpeed = it }
            SwitchRow("Reverse direction", null, invert) { invert = it; prefs.invertScroll = it }
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
