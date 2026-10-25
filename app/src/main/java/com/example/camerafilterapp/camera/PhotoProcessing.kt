package com.example.camerafilterapp.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PhotoProcessing"

/**
 * Device-maker still processing exposed through CameraX Extensions (Camera2 extensions, Android
 * 13+). Each mode is offered only where the device reports it for the current lens: the OEM
 * decides what the mode does, and many devices expose none of them to third-party apps.
 *
 * The filter still applies on top: CameraX keeps Preview effects in extension sessions, and photos
 * are filtered after capture. Face retouch and bokeh are deliberately not offered.
 */
enum class PhotoProcessing(val label: String, @ExtensionMode.Mode val extensionMode: Int) {
    STANDARD("Standard", ExtensionMode.NONE),
    HDR("HDR", ExtensionMode.HDR),
    NIGHT("Night", ExtensionMode.NIGHT),
}

/** Returns null if Extensions can't initialise; below Android 13 CameraX reports every mode unavailable. */
suspend fun loadExtensionsManager(context: Context): ExtensionsManager? = try {
    // Initialisation queries every camera's extension characteristics, so keep it off the main thread.
    withContext(Dispatchers.Default) {
        ExtensionsManager.getInstance(context, ProcessCameraProvider.getInstance(context).await())
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Camera extensions unavailable", e)
    null
}

/** Modes usable with [base]; always starts with [PhotoProcessing.STANDARD]. */
fun ExtensionsManager?.availableProcessing(base: CameraSelector): List<PhotoProcessing> =
    PhotoProcessing.entries.filter {
        it == PhotoProcessing.STANDARD || this?.isExtensionAvailable(base, it.extensionMode) == true
    }

/** [base] with [processing] enabled. Check [availableProcessing] first. */
fun ExtensionsManager?.selectorFor(base: CameraSelector, processing: PhotoProcessing): CameraSelector =
    if (this == null || processing == PhotoProcessing.STANDARD) {
        base
    } else {
        getExtensionEnabledCameraSelector(base, processing.extensionMode)
    }

/**
 * Suspends until the future completes. Cancelling the coroutine leaves the future running: the
 * futures awaited here (camera provider, controller initialisation) are shared.
 */
suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (e: ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
        } catch (e: CancellationException) {
            cont.cancel(e)
        }
    }, Runnable::run)
}
