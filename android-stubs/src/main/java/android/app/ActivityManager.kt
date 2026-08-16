package android.app

/** Minimal desktop stub for ActivityManager used by some plugins to read device memory. */
class ActivityManager {

    class MemoryInfo {
        @JvmField
        var totalMem: Long = 0L
        @JvmField
        var availMem: Long = 0L
        @JvmField
        var threshold: Long = 0L
        @JvmField
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