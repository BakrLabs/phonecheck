package dev.bakrlabs.phoneprobe

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONObject
import kotlin.math.abs

enum class Verdict { Ok, Bad, Info }

data class Line(val label: String, val claimed: String, val measured: String, val verdict: Verdict)

private const val BLOCK = 4L * 1024 * 1024

fun fmt(bytes: Long): String = "%.1f GB".format(bytes / 1073741824.0)

object Checks {
    private val apiProbes = listOf(
        23 to "android.security.keystore.KeyGenParameterSpec",
        24 to "android.os.LocaleList",
        26 to "android.app.NotificationChannel",
        28 to "android.hardware.biometrics.BiometricPrompt",
        29 to "android.app.role.RoleManager",
        30 to "android.view.WindowInsetsController",
        31 to "android.os.VibratorManager",
        33 to "android.window.OnBackInvokedCallback",
        34 to "android.credentials.CredentialManager",
    )

    private fun androidFor(api: Int) = when (api) {
        in 35..99 -> "15"
        34 -> "14"
        33 -> "13"
        31, 32 -> "12"
        30 -> "11"
        29 -> "10"
        28 -> "9"
        27 -> "8.1"
        26 -> "8.0"
        25 -> "7.1"
        24 -> "7.0"
        23 -> "6.0"
        22 -> "5.1"
        else -> "5.0"
    }

    fun totalRam(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    fun version(): List<Line> {
        val reported = Build.VERSION.SDK_INT
        var proven = 22
        var firstMissing = 0
        for ((api, cls) in apiProbes) {
            val present = runCatching { Class.forName(cls) }.isSuccess
            if (firstMissing != 0) continue
            if (present) proven = api else firstMissing = api
        }
        val liar = firstMissing != 0 && reported >= firstMissing
        val measured = if (firstMissing == 0) {
            "API $proven or newer (Android ${androidFor(proven)}+)"
        } else {
            "API $proven to ${firstMissing - 1} (Android ${androidFor(proven)} to ${androidFor(firstMissing - 1)})"
        }
        val lines = mutableListOf(
            Line(
                "Android version",
                "Android ${Build.VERSION.RELEASE} (API $reported)",
                measured,
                if (liar) Verdict.Bad else Verdict.Ok,
            )
        )
        val patch = runCatching { Build.VERSION.SECURITY_PATCH }.getOrDefault("")
        if (patch.isNotEmpty()) lines += Line("Security patch", patch, "-", Verdict.Info)
        return lines
    }

    fun storage(j: JSONObject): List<Line> {
        val err = j.getString("error")
        if (err.isNotEmpty()) return listOf(Line("Storage test", "-", err, Verdict.Info))

        val reported = fmt(j.getLong("total_bytes"))
        val written = j.getLong("written_bytes")
        val bad = j.getLong("bad_blocks")
        val real = j.getLong("est_real_bytes")

        val capacity = when {
            j.getBoolean("fake_detected") ->
                Line("Capacity", reported, "about ${fmt(real)} real, data wrapped around", Verdict.Bad)
            bad > 0 ->
                Line("Data integrity", "-", "$bad of ${written / BLOCK} blocks read back wrong", Verdict.Bad)
            j.getBoolean("cancelled") ->
                Line("Capacity", reported, "cancelled after ${fmt(written)}", Verdict.Info)
            j.getBoolean("complete") ->
                Line("Capacity", reported, "all free space verified", Verdict.Ok)
            else ->
                Line("Capacity", reported, "${fmt(written)} verified, partial test. Run Full to be sure", Verdict.Info)
        }
        return listOf(
            capacity,
            Line("Write speed", "-", "%.0f MB/s".format(j.getDouble("write_mbps")), Verdict.Info),
            Line("Read speed", "-", "%.0f MB/s".format(j.getDouble("read_mbps")), Verdict.Info),
        )
    }

    fun ram(claimed: Long, info: JSONObject, rep: JSONObject): List<Line> {
        val kernel = info.getLong("syscall_total")
        val meminfo = info.getLong("meminfo_total")
        val agree = kernel > 0 &&
            abs(claimed - kernel) <= kernel * 0.05 &&
            abs(meminfo - kernel) <= kernel * 0.05

        val target = rep.getLong("target_bytes")
        val touched = rep.getLong("touched_bytes")
        val stress = when {
            rep.getLong("bad_chunks") > 0 ->
                Line("Memory stress", fmt(target), "memory returned wrong data", Verdict.Bad)
            rep.getBoolean("cancelled") ->
                Line("Memory stress", fmt(target), "cancelled at ${fmt(touched)}", Verdict.Info)
            rep.getBoolean("stopped_low") ->
                Line("Memory stress", fmt(target), "stopped at ${fmt(touched)}. Close other apps and retry", Verdict.Info)
            else ->
                Line("Memory stress", fmt(target), "${fmt(touched)} filled and verified", Verdict.Ok)
        }
        return listOf(
            Line("Total RAM", fmt(claimed), "${fmt(kernel)} from the kernel", if (agree) Verdict.Ok else Verdict.Bad),
            stress,
            Line("Swap / virtual RAM", "-", fmt(info.getLong("swap_total")), Verdict.Info),
        )
    }

    fun cpu(info: JSONObject, bench: JSONObject): List<Line> {
        val reported = Runtime.getRuntime().availableProcessors()
        val present = info.getInt("present")
        val types = info.getJSONArray("core_types").let { arr -> List(arr.length()) { arr.getString(it) } }
        val grouped = types.groupingBy { it }.eachCount().entries
            .joinToString(", ") { "${it.value}x ${it.key}" }
            .ifEmpty { "not exposed by this kernel" }
        val socModel = runCatching { Build.SOC_MODEL }.getOrDefault("")
        val chip = listOf(Build.HARDWARE, socModel).filter { it.isNotBlank() && it != "unknown" }
            .joinToString(" / ").ifEmpty { "-" }
        val freqs = info.getJSONArray("max_khz").let { arr -> List(arr.length()) { arr.getLong(it) } }
        val top = freqs.maxOrNull()

        return listOf(
            Line(
                "Cores",
                "$reported",
                "$present present, ${info.getInt("online")} online",
                if (present == 0 || present == reported) Verdict.Ok else Verdict.Bad,
            ),
            Line("Chip", chip, grouped, Verdict.Info),
            Line("Top clock", "-", top?.let { "%.2f GHz".format(it / 1e6) } ?: "blocked by this device", Verdict.Info),
            Line("Single-core", "-", "%.0f Mops/s".format(bench.getDouble("single_mops")), Verdict.Info),
            Line("All cores", "-", "%.0f Mops/s".format(bench.getDouble("multi_mops")), Verdict.Info),
        )
    }
}
