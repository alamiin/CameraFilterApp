package com.example.camerafilterapp.camera

import android.view.ViewGroup
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Camera preview. Frames arrive already filtered by
 * [com.example.camerafilterapp.gl.FilterCameraEffect], so this view only displays them and
 * handles rotation, scaling and front-camera mirroring.
 */
@Composable
fun CameraPreview(controller: LifecycleCameraController, modifier: Modifier = Modifier) {
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
            }
        },
        onRelease = { view -> view.controller = null },
    )
}
