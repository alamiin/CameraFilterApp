package com.example.camerafilterapp.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AffineTest {

    /** Compares values, so 0 and -0 count as equal (data class equality would not). */
    private fun assertSame(expected: Affine, actual: Affine) =
        assertArrayEquals(expected.toMat4(), actual.toMat4(), 1e-6f)

    private val orientations = listOf(0, 90, 180, 270).flatMap { degrees ->
        val rotate = Affine.rotateClockwise(degrees)
        listOf(rotate, Affine.FLIP_X * rotate)
    }

    @Test
    fun inverseAlignedUndoesEveryRotationAndFlip() {
        orientations.forEach { assertSame(Affine.IDENTITY, it * it.inverseAligned()) }
    }

    @Test
    fun alignedDropsScaleAndKeepsOrientation() {
        // A 0.24x sensor-to-buffer scale combined with a 90-degree clockwise rotation.
        assertSame(Affine.rotateClockwise(90), Affine.aligned(0f, -0.24f, 0.24f, 0f))
        assertSame(Affine.FLIP_X, Affine.aligned(-0.5f, 0f, 0f, 0.5f))
        assertSame(Affine.IDENTITY, Affine.aligned(0.24f, 0f, 0f, 0.24f))
    }

    @Test
    fun uprightOutputNeedsNoFurtherRotation() {
        // CameraX rotated the output by the input's own rotation, as for a Preview-only effect.
        val input = Affine.rotateClockwise(90)
        val applied = Affine.aligned(0f, -0.24f, 0.24f, 0f)
        assertSame(Affine.IDENTITY, input * applied.inverseAligned())
    }

    @Test
    fun swapsAxesOnlyForQuarterTurns() {
        assertFalse(Affine.rotateClockwise(0).swapsAxes)
        assertTrue(Affine.rotateClockwise(90).swapsAxes)
        assertFalse(Affine.rotateClockwise(180).swapsAxes)
        assertTrue((Affine.FLIP_X * Affine.rotateClockwise(270)).swapsAxes)
    }
}
