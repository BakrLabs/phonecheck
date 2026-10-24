package dev.bakrlabs.phoneprobe

object Native {
    init {
        System.loadLibrary("probejni")
    }

    @JvmStatic external fun cpuInfo(): String
    @JvmStatic external fun cpuBench(): String
    @JvmStatic external fun ramInfo(): String
    @JvmStatic external fun ramTest(targetMb: Long, marker: String): String
    @JvmStatic external fun storageTest(dir: String, limitMb: Long): String
    @JvmStatic external fun progress(): String
    @JvmStatic external fun cancel()
    @JvmStatic external fun reset()
}
