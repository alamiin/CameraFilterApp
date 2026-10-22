package com.example.camerafilterapp

import com.example.camerafilterapp.filter.Look
import com.example.camerafilterapp.filter.Looks
import com.example.camerafilterapp.filter.LutCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.abs

class LookTest {

    private fun gridSamples() = sequence {
        val steps = listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f)
        for (r in steps) for (g in steps) for (b in steps) yield(floatArrayOf(r, g, b))
    }

    @Test
    fun lookIdsAreUnique() {
        assertEquals(Looks.all.size, Looks.all.map { it.id }.toSet().size)
    }

    @Test
    fun originalIsIdentity() {
        gridSamples().forEach { rgb ->
            val graded = rgb.copyOf().also { Look.ORIGINAL.grade(it) }
            for (c in 0..2) assertEquals(rgb[c], graded[c], 1e-5f)
        }
    }

    /** Looks whose colour comes from [Look.grade]; LUT-image looks are checked separately. */
    private val gradedLooks = Looks.all.filter { it.lutImage == null }

    @Test
    fun everyLookStaysInRange() {
        gradedLooks.forEach { look ->
            gridSamples().forEach { rgb ->
                look.grade(rgb)
                rgb.forEach { assertTrue("${look.id} produced $it", it in 0f..1f) }
            }
        }
    }

    @Test
    fun monoLooksWithoutTonesProduceNeutralGrey() {
        Looks.all.filter { it.mono != null && it.shadowTone == null && it.highlightTone == null }.forEach { look ->
            gridSamples().forEach { rgb ->
                look.grade(rgb)
                assertEquals(look.id, rgb[0], rgb[1], 1e-4f)
                assertEquals(look.id, rgb[1], rgb[2], 1e-4f)
            }
        }
    }

    /** Guards against looks becoming too faint to tell apart from the original. */
    @Test
    fun colourLooksAreClearlyVisible() {
        // Light-leak looks get most of their character from the shader glow, not the grade.
        gradedLooks.filter { !it.isIdentity && it.lightLeak == null }.forEach { look ->
            var total = 0f
            var count = 0
            gridSamples().forEach { rgb ->
                val graded = rgb.copyOf().also { look.grade(it) }
                total += (0..2).sumOf { abs(graded[it] - rgb[it]).toDouble() }.toFloat() / 3f
                count++
            }
            val mean = total / count
            assertTrue("${look.id} mean change $mean is too subtle", mean >= MIN_MEAN_CHANGE)
        }
    }

    @Test
    fun lutCornersMatchGrade() {
        val look = Looks.byId("cine")
        val lut = LutCache.get(look)
        val n = LutCache.SIZE
        for (r in listOf(0, n - 1)) for (g in listOf(0, n - 1)) for (b in listOf(0, n - 1)) {
            val expected = floatArrayOf(r / (n - 1f), g / (n - 1f), b / (n - 1f)).also { look.grade(it) }
            val index = (g * LutCache.WIDTH + b * n + r) * 4
            for (c in 0..2) {
                val actual = (lut.get(index + c).toInt() and 0xFF) / 255f
                assertEquals(expected[c], actual, 1f / 255f)
            }
        }
    }

    @Test
    fun neutralLookupImageGivesIdentityLut() {
        // 64 x 64 (16 levels, 4 tiles per row) and the usual 512 x 512 (64 levels, 8 per row).
        for (side in listOf(64, 512)) {
            val lut = LutCache.fromLookupImage(neutralLookupImage(side), side)
            val n = LutCache.SIZE
            for (g in 0 until n) for (b in 0 until n) for (r in 0 until n) {
                val index = (g * LutCache.WIDTH + b * n + r) * 4
                val expected = floatArrayOf(r / (n - 1f), g / (n - 1f), b / (n - 1f))
                // One level of rounding from the image plus one from the LUT.
                for (c in 0..2) assertEquals("side $side", expected[c], byteAt(lut, index + c), 2f / 255f)
            }
        }
    }

    @Test
    fun rejectsImagesThatAreNotLookupSized() {
        for (side in listOf(100, 256, 300)) {
            assertThrows(IllegalArgumentException::class.java) { LutCache.levelsFor(side) }
        }
        assertEquals(64, LutCache.levelsFor(512))
    }

    @Test
    fun lutImageAssetsAreValidAndClearlyVisible() {
        val looks = Looks.all.filter { it.lutImage != null }
        assertTrue("no LUT image looks to check", looks.isNotEmpty())
        looks.forEach { look ->
            val file = File("src/main/assets/${look.lutImage}")
            assertTrue("${look.id}: missing ${file.path}", file.isFile)
            val image = ImageIO.read(file)
            assertEquals("${look.id}: not square", image.width, image.height)
            val side = image.width
            val lut = LutCache.fromLookupImage(image.getRGB(0, 0, side, side, null, 0, side), side)

            val n = LutCache.SIZE
            var total = 0f
            for (g in 0 until n) for (b in 0 until n) for (r in 0 until n) {
                val index = (g * LutCache.WIDTH + b * n + r) * 4
                total += (abs(byteAt(lut, index) - r / (n - 1f)) + abs(byteAt(lut, index + 1) - g / (n - 1f)) +
                    abs(byteAt(lut, index + 2) - b / (n - 1f))) / 3f
            }
            val mean = total / (n * n * n)
            assertTrue("${look.id} mean change $mean is too subtle", mean >= MIN_MEAN_CHANGE)
        }
    }

    /** The do-nothing lookup image, in the layout tools/lut_png.py writes. */
    private fun neutralLookupImage(side: Int): IntArray {
        val levels = LutCache.levelsFor(side)
        val tiles = side / levels
        return IntArray(side * side) { i ->
            val x = i % side
            val y = i / side
            val b = (y / levels) * tiles + x / levels
            fun level(v: Int) = (v * 255f / (levels - 1)).toInt()
            (0xFF shl 24) or (level(x % levels) shl 16) or (level(y % levels) shl 8) or level(b)
        }
    }

    private fun byteAt(buffer: ByteBuffer, index: Int) = (buffer.get(index).toInt() and 0xFF) / 255f

    private companion object {
        /** About 13 of 255 levels on average across the colour cube. */
        const val MIN_MEAN_CHANGE = 0.05f
    }
}
