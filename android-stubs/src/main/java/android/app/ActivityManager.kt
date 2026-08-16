package android.app

/** Minimal desktop stub for ActivityManager used by some plugins to read device memory. */
class ActivityManager {

    class MemoryInfo {
        var totalMem: Long = 0L
        var availMem: Long = 0L
        var threshold: Long = 0L
        var lowMemory: Boolean = false
    }

    fun getMemoryInfo(outInfo: MemoryInfo) {
        val rt = Runtime.getRuntime()
        outInfo.availMem = rt.maxMemory() - rt.totalMemory() + rt.freeMemory()
        outInfo.totalMem = rt.maxMemory()
    }

    companion object {
        @JvmStatic
        fun getService(): ActivityManager = ActivityManager()
    }
}