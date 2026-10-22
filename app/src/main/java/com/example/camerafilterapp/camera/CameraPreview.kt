package com.example.camerafilterapp.camera

import android.view.ViewGroup
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/**
 * Camera preview. Frames arrive already filtered by
 * [com.example.camerafilterapp.gl.FilterCameraEffect], so this view only displays them and
 * handles rotation, scaling and front-camera mirroring.
 *
 * [onView] receives the view once created and null on release, for mapping taps with [focusAt].
 */
@Composable
fun CameraPreview(
    controller: LifecycleCameraController,
    modifier: Modifier = Modifier,
    onView: (PreviewView?) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // SurfaceView where supported: composited by the display hardware, lowest power.
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }.also(onView)
        },
        onRelease = { view ->
            view.controller = null
            onView(null)
        },
    )
}

/**
 * Focuses, exposes and white-balances on ([x], [y]) in [previewView]'s coordinates. The view's
 * metering point factory accounts for its crop and rotation, so the point lands on what was touched.
 * CameraX releases the lock after its default 5 s and returns to continuous autofocus.
 *
 * Returns false if the camera isn't bound or can't meter on that lens. [onResult] runs on the main
 * thread with whether focus locked, and is skipped when a newer tap or camera switch supersedes it.
 */
fun CameraController.focusAt(previewView: PreviewView, x: Float, y: Float, onResult: (Boolean) -> Unit): Boolean {
    val control = cameraControl ?: return false
    val info = cameraInfo ?: return false
    val action = FocusMeteringAction.Builder(previewView.meteringPointFactory.createPoint(x, y)).build()
    if (!info.isFocusMeteringSupported(action)) return false
    val future = control.startFocusAndMetering(action)
    future.addListener({
        val focused = try {
            future.get().isFocusSuccessful
        } catch (e: Exception) {
            if (e.cause is CameraControl.OperationCanceledException) return@addListener
            false
        }
        onResult(focused)
    }, ContextCompat.getMainExecutor(previewView.context))
    return true
}
