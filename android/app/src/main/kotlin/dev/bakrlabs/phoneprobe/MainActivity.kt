package dev.bakrlabs.phoneprobe

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0A0F0D)
private val PanelBg = Color(0xFF131A17)
private val Mint = Color(0xFF3DDBA4)
private val Red = Color(0xFFFF6B6B)
private val Muted = Color(0xFF8A9A93)

class MainActivity : ComponentActivity() {
    private val model: ProbeModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { App(model) }
    }
}

@Composable
private fun App(m: ProbeModel) {
    val scheme = darkColorScheme(primary = Mint, onPrimary = Color.Black, background = Bg, surface = PanelBg)
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Color.White) {
            LazyColumn(
                Modifier.systemBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Header() }
                item { Banner(m) }
                item { Panel("Android version") { m.versionLines.forEach { LineView(it) } } }
                item { StoragePanel(m) }
                item { RamPanel(m) }
                item { CpuPanel(m) }
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("PhoneCheck", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("by BakrLabs. Tests what the phone really has, not what Settings says.", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun Banner(m: ProbeModel) {
    val all = m.versionLines + m.storageLines + m.ramLines + m.cpuLines
    val bad = all.filter { it.verdict == Verdict.Bad }
    val done = m.storageLines.isNotEmpty() && m.ramLines.isNotEmpty() && m.cpuLines.isNotEmpty()
    val (color, text) = when {
        bad.isNotEmpty() -> Red to "Mismatch found: " + bad.joinToString(", ") { it.label }
        done -> Mint to "No mismatch found in the tests that ran"
        else -> Muted to "Run the storage, RAM and CPU tests below"
    }
    Text(
        text,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(14.dp),
    )
}

@Composable
private fun StoragePanel(m: ProbeModel) {
    var limit by remember { mutableLongStateOf(1024L) }
    Panel("Storage") {
        m.storageLines.forEach { LineView(it) }
        if (m.busy.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("1 GB", limit == 1024L) { limit = 1024L }
                Chip("4 GB", limit == 4096L) { limit = 4096L }
                Chip("Full", limit == 0L) { limit = 0L }
            }
            if (limit != 0L) {
                Text("A fake chip only shows up once the test passes its real size. Full is the only conclusive run: slow, keep the phone charging.", color = Muted, fontSize = 12.sp)
            }
        }
        RunBar(m, "storage", "Run storage test") { m.runStorage(limit) }
    }
}

@Composable
private fun RamPanel(m: ProbeModel) {
    Panel("RAM") {
        m.ramLines.forEach { LineView(it) }
        if (m.busy.isEmpty()) {
            Text("Close other apps first. Fills about 40% of the claimed RAM with data and checks it.", color = Muted, fontSize = 12.sp)
        }
        RunBar(m, "ram", "Run RAM test") { m.runRam() }
    }
}

@Composable
private fun CpuPanel(m: ProbeModel) {
    Panel("CPU") {
        m.cpuLines.forEach { LineView(it) }
        RunBar(m, "cpu", "Run CPU test") { m.runCpu() }
    }
}

@Composable
private fun RunBar(m: ProbeModel, name: String, label: String, onRun: () -> Unit) {
    if (m.busy == name) {
        val f = m.fraction
        if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(m.phase, color = Muted, fontSize = 12.sp)
        OutlinedButton(onClick = { m.cancel() }, Modifier.fillMaxWidth()) { Text("Cancel") }
    } else {
        Button(
            onClick = onRun,
            enabled = m.busy.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.Black),
        ) { Text(label) }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PanelBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun LineView(line: Line) {
    val tint = when (line.verdict) {
        Verdict.Ok -> Mint
        Verdict.Bad -> Red
        Verdict.Info -> Color.White
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(line.label, color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Claimed", color = Muted, fontSize = 11.sp)
                Text(line.claimed, fontSize = 14.sp)
            }
            Column(Modifier.weight(1.4f)) {
                Text("Measured", color = Muted, fontSize = 11.sp)
                Text(line.measured, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Mint else Color(0xFF212C27))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
