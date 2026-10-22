package com.example.camerafilterapp.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.example.camerafilterapp.filter.FilterSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Runs a still image through the same [FilterProgram] as the live preview, in a short-lived
 * offscreen EGL context, so saved photos match the preview exactly (LUT, grain, leaks, vignette).
 *
 * Blocking and GPU-bound; call from a background thread. Creates and destroys its own context.
 */
internal object StillRenderer {

    private const val TAG = "StillRenderer"

    /** Fixed grain seed for stills. */
    private const val STILL_SEED = 7.3f

    /**
     * Renders [source] rotated [rotationDegrees] clockwise with [settings] applied.
     * Takes ownership of [source] and recycles it once uploaded, to cap peak memory.
     */
    fun render(source: Bitmap, rotationDegrees: Int, settings: FilterSettings): Bitmap {
        val egl = EglCore(recordable = false)
        val pbuffer = egl.createPbuffer(1, 1)
        var program: FilterProgram? = null
        val ids = IntArray(3) // input texture, output texture, framebuffer
        try {
            check(egl.makeCurrent(pbuffer)) { "eglMakeCurrent failed: ${EglCore.eglErrorHex()}" }

            val input = fitToGpuLimits(source)
            val swapped = rotationDegrees % 180 != 0
            val outW = if (swapped) input.height else input.width
            val outH = if (swapped) input.width else input.height

            GLES20.glGenTextures(2, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            setLinearClamp()
            // Texture row 0 is the bitmap's top row.
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, input, 0)
            input.recycle()

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[1])
            setLinearClamp()
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outW, outH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glGenFramebuffers(1, ids, 2)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[2])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ids[1], 0,
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete: 0x${Integer.toHexString(status)}" }

            // Output position is upright with row 0 at the top (glReadPixels starts at GL row 0),
            // so the input lookup only needs the inverse rotation; texture rows already match.
            val texMatrix = Affine.unrotateClockwise(rotationDegrees).toMat4()
            val posMatrix = Affine.IDENTITY.toMat4()
            val grainCell = maxOf(1f, maxOf(outW, outH) / 1280f)

            program = FilterProgram(external = false)
            GLES20.glViewport(0, 0, outW, outH)
            program.draw(settings, ids[0], texMatrix, posMatrix, outW.toFloat() / outH, STILL_SEED, grainCell, outW, outH)

            val pixels = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "Still render failed: glError 0x${Integer.toHexString(error)}" }

            pixels.position(0)
            return createBitmap(outW, outH).apply { copyPixelsFromBuffer(pixels) }
        } finally {
            program?.release()
            if (ids[2] != 0) GLES20.glDeleteFramebuffers(1, ids, 2)
            GLES20.glDeleteTextures(2, ids, 0)
            egl.destroySurface(pbuffer)
            egl.release()
            if (!source.isRecycled) source.recycle()
        }
    }

    /** Downscales if the image exceeds the GPU's texture or viewport limits (rare above 8K). */
    private fun fitToGpuLimits(source: Bitmap): Bitmap {
        val values = IntArray(2)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, values, 0)
        val maxTexture = values[0]
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, values, 0)
        val limit = minOf(maxTexture, values[0], values[1])
        val longest = maxOf(source.width, source.height)
        if (longest <= limit) return source
        val factor = limit.toFloat() / longest
        Log.i(TAG, "Downscaling ${source.width}x${source.height} to fit GPU limit $limit")
        val scaled = source.scale((source.width * factor).roundToInt(), (source.height * factor).roundToInt())
        source.recycle()
        return scaled
    }

    private fun setLinearClamp() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
}
