package com.example.camerafilterapp.filter

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Bakes each [Look]'s grade into a 3D LUT stored as a 2D RGBA strip (GLES 2.0 has no 3D
 * textures): [SIZE] blue slices side by side, each SIZE x SIZE with red across and green down.
 * The shader interpolates bilinearly inside a slice and linearly between slices.
 *
 * Looks with a [Look.lutImage] are resampled from that PNG instead; see [fromLookupImage].
 */
object LutCache {

    private const val TAG = "LutCache"

    /** 33 points per axis is the common grading-LUT resolution; 143 KB per look. */
    const val SIZE = 33
    const val WIDTH = SIZE * SIZE
    const val HEIGHT = SIZE

    /** Bit offsets of red, green and blue in an ARGB pixel. */
    private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

    private val cache = ConcurrentHashMap<String, ByteBuffer>()

    @Volatile
    private var assets: AssetManager? = null

    /** Call before the camera starts, so looks backed by a LUT image can load it. */
    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    /** Returns a read-only view; safe to share between threads and GL contexts. */
    fun get(look: Look): ByteBuffer =
        cache.getOrPut(look.id) { build(look) }.asReadOnlyBuffer().apply { position(0) }

    /** Builds every LUT up front (about 1M grade evaluations) so the GL thread never stalls. */
    fun prewarm(looks: List<Look> = Looks.all) = looks.forEach { get(it) }

    private fun build(look: Look): ByteBuffer {
        val image = look.lutImage ?: return fromGrade(look)
        return try {
            val pixels = loadPixels(image)
            fromLookupImage(pixels.first, pixels.second)
        } catch (e: Exception) {
            // A bad asset shouldn't take the camera down; the look shows unfiltered instead.
            Log.e(TAG, "Could not load LUT image $image for ${look.id}", e)
            fromGrade(Look.ORIGINAL)
        }
    }

    private fun fromGrade(look: Look): ByteBuffer {
        val buffer = newBuffer()
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

    /**
     * Resamples a square PNG lookup image to this cache's [SIZE] grid with trilinear interpolation.
     *
     * The image holds N levels per channel as a grid of N x N tiles, sqrt(N) tiles per row: tile index
     * (left to right, top to bottom) is the blue level, red increases to the right inside a tile and
     * green increases downwards. The usual size is 512 x 512 with 64 levels; tools/lut_png.py writes a
     * neutral template in this layout for grading in a photo editor.
     *
     * @param pixels ARGB pixels in row-major order, as from [Bitmap.getPixels].
     * @param side image width and height.
     */
    internal fun fromLookupImage(pixels: IntArray, side: Int): ByteBuffer {
        val levels = levelsFor(side)
        val tiles = side / levels
        fun channel(r: Int, g: Int, b: Int, shift: Int): Float {
            val x = (b % tiles) * levels + r
            val y = (b / tiles) * levels + g
            return ((pixels[y * side + x] shr shift) and 0xFF) / 255f
        }

        val buffer = newBuffer()
        val scale = (levels - 1f) / (SIZE - 1)
        for (g in 0 until SIZE) {
            for (b in 0 until SIZE) {
                for (r in 0 until SIZE) {
                    val fr = r * scale
                    val fg = g * scale
                    val fb = b * scale
                    val r0 = fr.toInt()
                    val g0 = fg.toInt()
                    val b0 = fb.toInt()
                    val r1 = minOf(r0 + 1, levels - 1)
                    val g1 = minOf(g0 + 1, levels - 1)
                    val b1 = minOf(b0 + 1, levels - 1)
                    val wr = fr - r0
                    val wg = fg - g0
                    val wb = fb - b0
                    for (shift in CHANNEL_SHIFTS) {
                        fun lerpR(gi: Int, bi: Int) =
                            channel(r0, gi, bi, shift) * (1 - wr) + channel(r1, gi, bi, shift) * wr
                        fun lerpRG(bi: Int) = lerpR(g0, bi) * (1 - wg) + lerpR(g1, bi) * wg
                        buffer.put(toByte(lerpRG(b0) * (1 - wb) + lerpRG(b1) * wb))
                    }
                    buffer.put(0xFF.toByte())
                }
            }
        }
        buffer.position(0)
        return buffer
    }

    /** Levels per channel for a lookup image of this size: N^3 pixels arranged in a square. */
    internal fun levelsFor(side: Int): Int {
        var n = 2
        while (n * n * n < side * side) n++
        require(n * n * n == side * side && side % n == 0) {
            "$side x $side is not a lookup image size (expected e.g. 512 x 512 for 64 levels)"
        }
        return n
    }

    private fun loadPixels(path: String): Pair<IntArray, Int> {
        val manager = checkNotNull(assets) { "LutCache.init was not called" }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = manager.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Not a decodable image")
        try {
            require(bitmap.width == bitmap.height) { "LUT image must be square, was ${bitmap.width} x ${bitmap.height}" }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels to bitmap.width
        } finally {
            bitmap.recycle()
        }
    }

    private fun newBuffer(): ByteBuffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder())

    private fun toByte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
}
