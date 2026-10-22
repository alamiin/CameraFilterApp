package com.example.camerafilterapp

import com.example.camerafilterapp.filter.Look
import com.example.camerafilterapp.filter.Looks
import com.example.camerafilterapp.filter.LutCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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

    @Test
    fun everyLookStaysInRange() {
        Looks.all.forEach { look ->
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
        Looks.all.filter { !it.isIdentity && it.lightLeak == null }.forEach { look ->
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

    private companion object {
        /** About 13 of 255 levels on average across the colour cube. */
        const val MIN_MEAN_CHANGE = 0.05f
    }
}
