package dev.bakrlabs.phoneprobe

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ProbeModel(app: Application) : AndroidViewModel(app) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val marker = File(app.filesDir, "ram.marker")
    private val workDir = File(app.filesDir, "probe")

    var versionLines by mutableStateOf(emptyList<Line>())
        private set
    var storageLines by mutableStateOf(emptyList<Line>())
        private set
    var ramLines by mutableStateOf(emptyList<Line>())
        private set
    var cpuLines by mutableStateOf(emptyList<Line>())
        private set
    var busy by mutableStateOf("")
        private set
    var fraction by mutableStateOf<Float?>(null)
        private set
    var phase by mutableStateOf("")
        private set

    init {
        versionLines = Checks.version()
        if (marker.exists()) {
            val at = marker.readText().trim().toLongOrNull() ?: 0L
            ramLines = listOf(
                Line("Previous RAM test", "-", "interrupted at ${fmt(at)} (killed by the system, or the app was closed)", Verdict.Info)
            )
            marker.delete()
        }
    }

    fun runStorage(limitMb: Long) = start("storage") {
        storageLines = Checks.storage(JSONObject(Native.storageTest(workDir.path, limitMb)))
    }

    fun runRam() = start("ram") {
        val claimed = Checks.totalRam(getApplication())
        val info = JSONObject(Native.ramInfo())
        val cap = if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) 1500L else Long.MAX_VALUE
        val target = minOf(claimed * 4 / 10 / 1048576L, cap)
        val rep = JSONObject(Native.ramTest(target, marker.path))
        ramLines = Checks.ram(claimed, info, rep)
    }

    fun runCpu() = start("cpu") {
        val info = JSONObject(Native.cpuInfo())
        val bench = JSONObject(Native.cpuBench())
        cpuLines = Checks.cpu(info, bench)
    }

    fun cancel() = Native.cancel()

    private fun start(name: String, work: () -> Unit) {
        if (busy.isNotEmpty()) return
        busy = name
        fraction = null
        phase = ""
        Native.reset()
        scope.launch {
            val poller = launch {
                while (isActive) {
                    poll()
                    delay(250)
                }
            }
            try {
                withContext(Dispatchers.Default) { work() }
            } finally {
                poller.cancel()
                busy = ""
            }
        }
    }

    private fun poll() {
        val p = Native.progress().split(' ').mapNotNull { it.toLongOrNull() }
        if (p.size < 3) return
        phase = when (p[0].toInt()) {
            1 -> "Writing test data"
            2 -> "Reading it back"
            3 -> "Filling memory"
            4 -> "Checking memory"
            5 -> "Single-core run"
            6 -> "All-core run"
            else -> ""
        }
        fraction = if (p[2] > 0) (p[1].toFloat() / p[2]).coerceIn(0f, 1f) else null
    }
}
