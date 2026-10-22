package com.example.camerafilterapp.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.graphics.createBitmap
import com.example.camerafilterapp.filter.FilterSettings
import com.example.camerafilterapp.filter.Look
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders camera frames from an external OES texture through [FilterProgram] into any number of
 * output surfaces (preview, video encoder), and renders per-look thumbnails on request.
 *
 * Not thread-safe: every method must be called on the single GL thread that owns the context.
 */
internal class GlFilterRenderer {

    private var egl: EglCore? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program: FilterProgram? = null
    private val outputSurfaces = HashMap<Surface, EGLSurface>()

    var textureId = 0
        private set
    val isInitialised: Boolean get() = egl != null

    var settings = FilterSettings()

    /** Clockwise rotation and mirroring that make the camera buffer upright (from CameraX). */
    var rotationDegrees = 0
    var mirroring = false

    private var frameCount = 0

    private var thumbFbo = 0
    private var thumbTexture = 0
    private var thumbBuffer: ByteBuffer? = null

    fun init() {
        val core = EglCore(recordable = true)
        egl = core
        // 1x1 pbuffer keeps the context current when no output surface exists yet.
        pbuffer = core.createPbuffer(1, 1)
        check(core.makeCurrent(pbuffer)) { "eglMakeCurrent failed: ${EglCore.eglErrorHex()}" }
        program = FilterProgram(external = true)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGl("texture setup")
    }

    fun registerOutput(surface: Surface) {
        outputSurfaces[surface] = requireEgl().createWindowSurface(surface)
    }

    fun unregisterOutput(surface: Surface) {
        val eglSurface = outputSurfaces.remove(surface) ?: return
        val core = requireEgl()
        core.makeCurrent(pbuffer)
        core.destroySurface(eglSurface)
    }

    /** Ensures the context is current so SurfaceTexture.updateTexImage() can run. */
    fun makeContextCurrent() {
        requireEgl().makeCurrent(pbuffer)
        frameCount++
    }

    /** @param texMatrix CameraX's per-output matrix (SurfaceTexture transform plus crop). */
    fun render(surface: Surface, size: Size, texMatrix: FloatArray, timestampNs: Long) {
        val core = requireEgl()
        val eglSurface = outputSurfaces[surface] ?: return
        if (!core.makeCurrent(eglSurface)) {
            Log.w(TAG, "eglMakeCurrent failed: ${EglCore.eglErrorHex()}")
            return
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, size.width, size.height)

        // Output buffers are still sensor-oriented; positional effects need upright coordinates.
        val upright = uprightTransform() * Affine.FLIP_Y
        val swapped = rotationDegrees % 180 != 0
        val aspect = if (swapped) size.height.toFloat() / size.width else size.width.toFloat() / size.height
        val grainCell = maxOf(1f, maxOf(size.width, size.height) / 1280f)

        requireProgram().draw(
            settings, textureId, texMatrix, upright.toMat4(), aspect, seed(), grainCell, size.width, size.height,
        )
        if (!core.swapBuffers(eglSurface, timestampNs)) {
            Log.w(TAG, "eglSwapBuffers failed: ${EglCore.eglErrorHex()}")
        }
    }

    /**
     * Renders every look into a square, upright, centre-cropped thumbnail of the current frame.
     * All tiles go into one framebuffer and are read back with a single glReadPixels.
     *
     * @param outputTexMatrix CameraX's matrix for one output of the current frame.
     * @param outputSize that output's size (sensor orientation, before rotation).
     */
    fun renderThumbnails(
        looks: List<Look>,
        outputTexMatrix: FloatArray,
        outputSize: Size,
        thumbSize: Int,
    ): List<Bitmap> {
        val core = requireEgl()
        core.makeCurrent(pbuffer)
        val cols = THUMB_COLUMNS
        val rows = (looks.size + cols - 1) / cols
        val width = cols * thumbSize
        val height = rows * thumbSize
        ensureThumbTarget(width, height)

        // Thumbnail position (upright, top-left origin) -> output image -> output GL coords -> texture.
        val swapped = rotationDegrees % 180 != 0
        val uprightW = if (swapped) outputSize.height else outputSize.width
        val uprightH = if (swapped) outputSize.width else outputSize.height
        val crop = if (uprightW > uprightH) {
            Affine.scaleAboutCentre(uprightH.toFloat() / uprightW, 1f)
        } else {
            Affine.scaleAboutCentre(1f, uprightW.toFloat() / uprightH)
        }
        val mirror = if (mirroring) Affine.FLIP_X else Affine.IDENTITY
        val toTexCoord = Affine.FLIP_Y * Affine.unrotateClockwise(rotationDegrees) * mirror * crop
        val texMatrix = FloatArray(16)
        Matrix.multiplyMM(texMatrix, 0, outputTexMatrix, 0, toTexCoord.toMat4(), 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, thumbFbo)
        val program = requireProgram()
        val identity = Affine.IDENTITY.toMat4()
        looks.forEachIndexed { i, look ->
            // Readback starts at row 0, so tile row 0 is the top row of the atlas bitmap.
            GLES20.glViewport((i % cols) * thumbSize, (i / cols) * thumbSize, thumbSize, thumbSize)
            program.draw(FilterSettings(look), textureId, texMatrix, identity, 1f, 0f, 1f, thumbSize, thumbSize)
        }

        val buffer = thumbBuffer!!.apply { position(0) }
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        checkGl("thumbnails")

        val atlas = createBitmap(width, height).apply { copyPixelsFromBuffer(buffer) }
        return looks.indices.map { i ->
            Bitmap.createBitmap(atlas, (i % cols) * thumbSize, (i / cols) * thumbSize, thumbSize, thumbSize)
        }.also { atlas.recycle() }
    }

    fun release() {
        val core = egl ?: return
        core.makeCurrent(pbuffer)
        program?.release()
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        if (thumbFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(thumbFbo), 0)
        if (thumbTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(thumbTexture), 0)
        outputSurfaces.values.forEach { core.destroySurface(it) }
        outputSurfaces.clear()
        if (pbuffer != EGL14.EGL_NO_SURFACE) core.destroySurface(pbuffer)
        core.release()
        egl = null
        program = null
        pbuffer = EGL14.EGL_NO_SURFACE
        textureId = 0
        thumbFbo = 0
        thumbTexture = 0
        thumbBuffer = null
    }

    private fun uprightTransform(): Affine {
        val rotate = Affine.rotateClockwise(rotationDegrees)
        return if (mirroring) Affine.FLIP_X * rotate else rotate
    }

    private fun ensureThumbTarget(width: Int, height: Int) {
        if (thumbFbo != 0 && thumbBuffer?.capacity() == width * height * 4) return
        if (thumbFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(thumbFbo), 0)
        if (thumbTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(thumbTexture), 0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        thumbTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, thumbTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glGenFramebuffers(1, ids, 0)
        thumbFbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, thumbFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, thumbTexture, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Thumbnail framebuffer incomplete: 0x${Integer.toHexString(status)}" }
        thumbBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    }

    /** Animated grain: a small repeating seed keeps sin() in the hash precise. */
    private fun seed() = (frameCount % 64) * 1.618f

    private fun requireEgl() = checkNotNull(egl) { "Renderer not initialised" }

    private fun requireProgram() = checkNotNull(program) { "Renderer not initialised" }

    private fun checkGl(op: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$op: glError 0x${Integer.toHexString(error)}" }
    }

    private companion object {
        const val TAG = "GlFilterRenderer"
        const val THUMB_COLUMNS = 8
    }
}
