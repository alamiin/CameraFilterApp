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
 * Looks with a [Look.lutImage] are resampled from that file instead: a PNG lookup image (see
 * [fromLookupImage]) or an Adobe/Resolve `.cube` file (see [fromCube]).
 */
object LutCache {

    private const val TAG = "LutCache"

    /** 33 points per axis is the common grading-LUT resolution; 143 KB per look. */
    const val SIZE = 33
    const val WIDTH = SIZE * SIZE
    const val HEIGHT = SIZE

    /** Bit offsets of red, green and blue in an ARGB pixel. */
    private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

    private val WHITESPACE = Regex("\\s+")

    private val cache = ConcurrentHashMap<String, ByteBuffer>()

    @Volatile
    private var assets: AssetManager? = null

    /** Call before the camera starts, so looks backed by a LUT image can load it. */
    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    /**
     * Returns a read-only view; safe to share between threads and GL contexts. A look not yet built
     * is built on the calling thread, or waited for if another thread is already building it.
     */
    fun get(look: Look): ByteBuffer =
        cache.computeIfAbsent(look.id) { build(look) }.asReadOnlyBuffer().apply { position(0) }

    /** True once every look in [looks] is built, so [get] returns without blocking. */
    fun isReady(looks: List<Look>): Boolean = looks.all { cache.containsKey(it.id) }

    /** Builds every LUT up front (about 1M grade evaluations) so the GL thread never stalls. */
    fun prewarm(looks: List<Look> = Looks.all) = looks.forEach { get(it) }

    private fun build(look: Look): ByteBuffer {
        val image = look.lutImage ?: return fromGrade(look)
        return try {
            if (image.endsWith(".cube", ignoreCase = true)) {
                fromCube(loadText(image))
            } else {
                val pixels = loadPixels(image)
                fromLookupImage(pixels.first, pixels.second)
            }
        } catch (e: Exception) {
            // A bad asset shouldn't take the camera down; the look shows unfiltered instead.
            Log.e(TAG, "Could not load LUT $image for ${look.id}", e)
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
        return resample(levels) { r, g, b, channel ->
            val x = (b % tiles) * levels + r
            val y = (b / tiles) * levels + g
            ((pixels[y * side + x] shr CHANNEL_SHIFTS[channel]) and 0xFF) / 255f
        }
    }

    /**
     * Resamples the text of a 3D `.cube` LUT (Adobe / DaVinci Resolve format) to this cache's [SIZE]
     * grid. Data rows are "R G B" floats with red changing fastest, then green, then blue.
     *
     * Only the 0..1 input domain is supported, which is what photo and video tools export.
     */
    internal fun fromCube(text: String): ByteBuffer {
        var levels = 0
        var data: FloatArray? = null
        var count = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(WHITESPACE)
            if (parts[0].toFloatOrNull() != null) {
                val values = requireNotNull(data) { "Data row before LUT_3D_SIZE" }
                require(parts.size == 3) { "Expected 3 values per row, got \"$line\"" }
                require(count + 3 <= values.size) { "More than $levels^3 data rows" }
                for (p in parts) values[count++] = p.toFloat()
                continue
            }
            when (parts[0]) {
                "LUT_3D_SIZE" -> {
                    levels = parts[1].toInt()
                    require(levels in 2..256) { "Unsupported LUT_3D_SIZE $levels" }
                    data = FloatArray(levels * levels * levels * 3)
                }
                "LUT_1D_SIZE" -> throw IllegalArgumentException("1D .cube LUTs are not supported")
                "DOMAIN_MIN" -> require(parts.drop(1).all { it.toFloat() == 0f }) { "Unsupported $line" }
                "DOMAIN_MAX" -> require(parts.drop(1).all { it.toFloat() == 1f }) { "Unsupported $line" }
                "LUT_3D_INPUT_RANGE" -> require(parts[1].toFloat() == 0f && parts[2].toFloat() == 1f) { "Unsupported $line" }
                // TITLE and any other keywords don't affect the colours.
            }
        }
        val values = requireNotNull(data) { "Missing LUT_3D_SIZE" }
        require(count == values.size) { "Expected ${values.size / 3} data rows, got ${count / 3}" }
        return resample(levels) { r, g, b, channel -> values[((b * levels + g) * levels + r) * 3 + channel] }
    }

    /**
     * Trilinearly resamples a source LUT with [levels] points per axis to [SIZE].
     *
     * @param sample the source value (0..1) of channel 0, 1 or 2 (red, green, blue) at grid point r, g, b.
     */
    private fun resample(levels: Int, sample: (r: Int, g: Int, b: Int, channel: Int) -> Float): ByteBuffer {
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
                    for (channel in 0..2) {
                        fun lerpR(gi: Int, bi: Int) =
                            sample(r0, gi, bi, channel) * (1 - wr) + sample(r1, gi, bi, channel) * wr
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

    private fun loadText(path: String): String {
        val manager = checkNotNull(assets) { "LutCache.init was not called" }
        return manager.open(path).bufferedReader().use { it.readText() }
    }

    private fun newBuffer(): ByteBuffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder())

    private fun toByte(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
}
