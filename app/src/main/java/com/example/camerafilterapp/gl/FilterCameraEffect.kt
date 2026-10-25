package com.example.camerafilterapp.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.example.camerafilterapp.filter.FilterSettings
import com.example.camerafilterapp.filter.Looks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * CameraX effect that runs [FilterSurfaceProcessor] on the shared preview + video stream,
 * so the live preview and the recorded video receive identical, shader-filtered frames.
 *
 * CameraX only attaches an effect when every one of its targets is bound, so use [previewOnly]
 * while VideoCapture is unbound. Set one or the other on the controller, never both: with both,
 * CameraX would attach the second to the shared stream's Preview child and filter it twice.
 */
class FilterCameraEffect private constructor(targets: Int, private val processor: FilterSurfaceProcessor) :
    CameraEffect(
        targets,
        processor.executor,
        processor,
        { error -> Log.e(TAG, "Camera effect error", error) },
    ) {

    constructor() : this(PREVIEW or VIDEO_CAPTURE, FilterSurfaceProcessor())

    /**
     * The same filter applied to Preview alone, sharing this effect's GL thread, settings and
     * thumbnails. Without VideoCapture there is no stream sharing, so CameraX skips its own copy
     * pass and the preview is resampled once instead of twice.
     */
    val previewOnly: CameraEffect by lazy { FilterCameraEffect(PREVIEW, processor) }

    /** Live thumbnails of every look in [Looks.all], keyed by look id. Refreshed every few seconds. */
    val thumbnails: StateFlow<Map<String, Bitmap>> get() = processor.thumbnails

    /** Safe to call from any thread; applied from the next rendered frame. */
    fun setSettings(settings: FilterSettings) = processor.setSettings(settings)

    /** Call after the camera is unbound. GL resources are freed once CameraX returns the input surface. Also releases [previewOnly]. */
    fun release() = processor.release()

    private companion object {
        const val TAG = "FilterCameraEffect"
    }
}

/**
 * Receives camera frames on a [SurfaceTexture] and renders them through [GlFilterRenderer]
 * into every output CameraX hands us. All state is confined to one GL thread.
 */
internal class FilterSurfaceProcessor : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private val thread = HandlerThread("FilterGlThread").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { if (!handler.post(it)) Log.w(TAG, "GL thread gone, task dropped") }

    private val renderer = GlFilterRenderer()
    private val outputs = LinkedHashMap<SurfaceOutput, Surface>()
    private var activeInputs = 0
    private var released = false

    private val textureMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private val thumbnailMatrix = FloatArray(16)
    private var thumbnailSourceSize: Size? = null

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()
    private var lastThumbnailsAt = 0L
    private var framesSinceInput = 0
    private val frameStats = FrameStats()

    fun setSettings(settings: FilterSettings) {
        handler.post { renderer.settings = settings }
    }

    fun release() {
        handler.post {
            if (released) return@post
            released = true
            outputs.keys.forEach { it.close() }
            outputs.values.forEach { renderer.unregisterOutput(it) }
            outputs.clear()
            maybeTearDown()
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        ensureInitialised()
        framesSinceInput = 0
        frameStats.reset()
        request.setTransformationInfoListener(executor) { info ->
            renderer.rotationDegrees = info.rotationDegrees
            renderer.mirroring = info.isMirroring
            lastThumbnailsAt = 0L // orientation changed: refresh thumbnails soon
        }
        val surfaceTexture = SurfaceTexture(renderer.textureId).apply {
            setDefaultBufferSize(request.resolution.width, request.resolution.height)
        }
        val surface = Surface(surfaceTexture)
        activeInputs++
        request.provideSurface(surface, executor) {
            request.clearTransformationInfoListener()
            surfaceTexture.setOnFrameAvailableListener(null)
            surfaceTexture.release()
            surface.release()
            activeInputs--
            maybeTearDown()
        }
        surfaceTexture.setOnFrameAvailableListener(this, handler)
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released) {
            surfaceOutput.close()
            return
        }
        ensureInitialised()
        val surface = surfaceOutput.getSurface(executor) { event ->
            // CameraX wants the output back (rebind, camera switch, recording surface change).
            event.surfaceOutput.close()
            outputs.remove(event.surfaceOutput)?.let { renderer.unregisterOutput(it) }
        }
        try {
            renderer.registerOutput(surface)
        } catch (e: IllegalStateException) {
            surfaceOutput.close()
            throw ProcessingException().apply { initCause(e) }
        }
        outputs[surfaceOutput] = surface
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (released) return
        val startNs = SystemClock.elapsedRealtimeNanos()
        // Always latch the frame, even with no outputs, or the camera's buffer queue stalls.
        renderer.makeContextCurrent()
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureMatrix)
        val timestamp = surfaceTexture.timestamp
        thumbnailSourceSize = null
        for ((output, surface) in outputs) {
            // Combines the SurfaceTexture transform with this output's crop/rotation/mirroring.
            output.updateTransformMatrix(outputMatrix, textureMatrix)
            renderer.render(surface, output.size, outputMatrix, timestamp)
            if (thumbnailSourceSize == null) {
                // Thumbnails reuse an output's matrix: the raw SurfaceTexture matrix can carry a
                // device-specific rotation that CameraX has already accounted for here.
                outputMatrix.copyInto(thumbnailMatrix)
                thumbnailSourceSize = output.size
            }
        }
        framesSinceInput++
        frameStats.onFrame(timestamp, SystemClock.elapsedRealtimeNanos() - startNs)
        maybeRenderThumbnails()
    }

    private fun maybeRenderThumbnails() {
        val sourceSize = thumbnailSourceSize ?: return
        val now = SystemClock.elapsedRealtime()
        // Skip the first frames after (re)start while auto-exposure settles.
        if (framesSinceInput < WARMUP_FRAMES || now - lastThumbnailsAt < THUMBNAIL_INTERVAL_MS) return
        lastThumbnailsAt = now
        try {
            val bitmaps = renderer.renderThumbnails(Looks.all, thumbnailMatrix, sourceSize, THUMBNAIL_SIZE)
            _thumbnails.value = Looks.all.zip(bitmaps) { look, bitmap -> look.id to bitmap }.toMap()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Thumbnail render failed", e)
        }
    }

    private fun ensureInitialised() {
        if (renderer.isInitialised) return
        try {
            renderer.init()
        } catch (e: IllegalStateException) {
            renderer.release()
            throw ProcessingException().apply { initCause(e) }
        }
    }

    private fun maybeTearDown() {
        if (!released || activeInputs > 0) return
        renderer.release()
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "FilterSurfaceProcessor"
        const val THUMBNAIL_SIZE = 128
        const val THUMBNAIL_INTERVAL_MS = 2_000L
        const val WARMUP_FRAMES = 15
    }
}
