package com.example.camerafilterapp.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** Minimal EGL 1.4 wrapper for an OpenGL ES 2.0 context. Use from a single thread. */
internal class EglCore(recordable: Boolean) {

    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    val context: EGLContext

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = mutableListOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
        )
        // Required for surfaces that feed MediaCodec.
        if (recordable) attribs += listOf(EGL_RECORDABLE_ANDROID, 1)
        attribs += EGL14.EGL_NONE

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs.toIntArray(), 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No RGBA8888 ES2 EGL config (recordable=$recordable)"
        }
        config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${eglErrorHex()}" }
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${eglErrorHex()}" }
        return eglSurface
    }

    fun createPbuffer(width: Int, height: Int): EGLSurface {
        val eglSurface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed: ${eglErrorHex()}" }
        return eglSurface
    }

    fun makeCurrent(surface: EGLSurface): Boolean = EGL14.eglMakeCurrent(display, surface, surface, context)

    fun destroySurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun swapBuffers(surface: EGLSurface, presentationTimeNs: Long): Boolean {
        // The encoder uses this as the frame's timestamp; without it timing drifts.
        EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs)
        return EGL14.eglSwapBuffers(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        // No eglTerminate: the default display is shared with the app's UI renderer.
        EGL14.eglReleaseThread()
    }

    companion object {
        // EGLExt.EGL_RECORDABLE_ANDROID is API 26; the value is fixed by the extension spec.
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        fun eglErrorHex(): String = "0x" + Integer.toHexString(EGL14.eglGetError())
    }
}
