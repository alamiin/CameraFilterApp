package com.example.camerafilterapp.gl

import android.util.Log

/**
 * Measures the live filter pipeline: camera frame rate, frames the camera skipped (gaps between
 * sensor timestamps well above the median), and CPU time spent submitting each frame on the GL
 * thread. GPU execution time is not included; a stall shows up as skipped frames instead.
 *
 * Off by default. Enable with `adb shell setprop log.tag.FrameStats DEBUG`, then read
 * `adb logcat -s FrameStats` (takes effect on the next camera start). Allocation-free per frame;
 * GL thread only.
 */
internal class FrameStats {

    private val intervalsNs = LongArray(WINDOW_FRAMES)
    private val sortScratch = LongArray(WINDOW_FRAMES)
    private var count = 0
    private var lastTimestampNs = 0L
    private var windowStartNs = 0L
    private var renderTotalNs = 0L
    private var renderMaxNs = 0L
    private var enabled = Log.isLoggable(TAG, Log.DEBUG)

    /** Call when the input stream restarts, so the gap across the restart isn't counted. */
    fun reset() {
        enabled = Log.isLoggable(TAG, Log.DEBUG)
        count = 0
        lastTimestampNs = 0L
        windowStartNs = 0L
        renderTotalNs = 0L
        renderMaxNs = 0L
    }

    /**
     * @param timestampNs the frame's sensor timestamp (SurfaceTexture.getTimestamp()).
     * @param renderNs wall time spent latching and drawing this frame into every output.
     */
    fun onFrame(timestampNs: Long, renderNs: Long) {
        if (!enabled) return
        if (lastTimestampNs == 0L) {
            lastTimestampNs = timestampNs
            windowStartNs = timestampNs
            return
        }
        intervalsNs[count++] = timestampNs - lastTimestampNs
        lastTimestampNs = timestampNs
        renderTotalNs += renderNs
        renderMaxNs = maxOf(renderMaxNs, renderNs)
        if (count == WINDOW_FRAMES) report(timestampNs)
    }

    private fun report(nowNs: Long) {
        intervalsNs.copyInto(sortScratch)
        sortScratch.sort()
        val medianNs = sortScratch[count / 2]
        // A gap of 1.5x the typical interval means at least one frame never arrived.
        val skipped = intervalsNs.sumOf { if (it > medianNs * 3 / 2) (it + medianNs / 2) / medianNs - 1 else 0L }
        val fps = count * 1e9 / (nowNs - windowStartNs)
        Log.d(
            TAG,
            "%.1f fps (median %.1f ms), %d skipped, render avg %.2f ms max %.2f ms over %d frames".format(
                fps, medianNs / 1e6, skipped, renderTotalNs / 1e6 / count, renderMaxNs / 1e6, count,
            ),
        )
        count = 0
        windowStartNs = nowNs
        renderTotalNs = 0L
        renderMaxNs = 0L
        enabled = Log.isLoggable(TAG, Log.DEBUG)
    }

    private companion object {
        const val TAG = "FrameStats"

        /** About 5 s at 30 fps. */
        const val WINDOW_FRAMES = 150
    }
}
