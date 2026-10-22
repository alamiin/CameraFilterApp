package com.example.camerafilterapp.filter

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Bakes each [Look]'s grade into a 3D LUT stored as a 2D RGBA strip (GLES 2.0 has no 3D
 * textures): [SIZE] blue slices side by side, each SIZE x SIZE with red across and green down.
 * The shader interpolates bilinearly inside a slice and linearly between slices.
 */
object LutCache {

    /** 33 points per axis is the common grading-LUT resolution; 143 KB per look. */
    const val SIZE = 33
    const val WIDTH = SIZE * SIZE
    const val HEIGHT = SIZE

    private val cache = ConcurrentHashMap<String, ByteBuffer>()

    /** Returns a read-only view; safe to share between threads and GL contexts. */
    fun get(look: Look): ByteBuffer =
        cache.getOrPut(look.id) { build(look) }.asReadOnlyBuffer().apply { position(0) }

    /** Builds every LUT up front (about 1M grade evaluations) so the GL thread never stalls. */
    fun prewarm(looks: List<Look> = Looks.all) = looks.forEach { get(it) }

    private fun build(look: Look): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder())
        val rgb = FloatArray(3)
        val step = 1f / (SIZE - 1)
        for (g in 0 until SIZE) {
            for (b in 0 until SIZE) {
                for (r in 0 until SIZE) {
                    rgb[0] = r * step
                    rgb[1] = g * step
                    rgb[2] = b * step
                    look.grade(rgb)
                    // Row g, column b * SIZE + r: written in row-major order.
                    buffer.put(toByte(rgb[0]))
                    buffer.put(toByte(rgb[1]))
                    buffer.put(toByte(rgb[2]))
                    buffer.put(0xFF.toByte())
                }
            }
        }
        buffer.position(0)
        return buffer
    }

    private fun toByte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
}
