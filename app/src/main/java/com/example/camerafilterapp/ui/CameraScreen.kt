package com.example.camerafilterapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camerafilterapp.camera.CameraPreview
import com.example.camerafilterapp.camera.FilteredPhotoSaver
import com.example.camerafilterapp.camera.focusAt
import com.example.camerafilterapp.camera.newVideoOutputOptions
import com.example.camerafilterapp.filter.FilterSettings
import com.example.camerafilterapp.filter.Look
import com.example.camerafilterapp.filter.LookCategory
import com.example.camerafilterapp.filter.Looks
import com.example.camerafilterapp.filter.LutCache
import com.example.camerafilterapp.gl.FilterCameraEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraScreen"

private enum class CaptureMode { PHOTO, VIDEO }

/**
 * Photo mode keeps video bound so the preview stays on the shared stream the filter effect is
 * attached to. Video mode drops image capture: a full-resolution JPEG stream limits the shared
 * stream to the display size on many devices (720p on a Galaxy A22), so this frees it to reach
 * 1080p. Switching mode briefly restarts the camera.
 */
private fun CaptureMode.useCases(): Int = when (this) {
    CaptureMode.PHOTO -> CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE
    CaptureMode.VIDEO -> CameraController.VIDEO_CAPTURE
}

/** A tap-to-focus point in preview coordinates; [focused] is null while the lens is still scanning. */
private data class FocusTap(val at: Offset, val focused: Boolean? = null)

private val requiredPermissions: Array<String> =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        arrayOf(Manifest.permission.CAMERA)
    }

/** Requested alongside the required set, but the app works without it (silent video). */
private val optionalPermissions = arrayOf(Manifest.permission.RECORD_AUDIO)

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasAllPermissions() = requiredPermissions.all { isGranted(it) }

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasAllPermissions()) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askedOnce = true
        granted = context.hasAllPermissions()
    }
    val permissionsToRequest = requiredPermissions + optionalPermissions

    if (granted) {
        CameraContent()
    } else {
        PermissionRationale(
            askedOnce = askedOnce,
            onRequest = { launcher.launch(permissionsToRequest) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                )
            },
        )
        if (!askedOnce) {
            LaunchedEffect(Unit) { launcher.launch(permissionsToRequest) }
        }
    }
}

@Composable
private fun CameraContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var lookId by rememberSaveable { mutableStateOf(Look.ORIGINAL.id) }
    var category by rememberSaveable { mutableStateOf<LookCategory?>(null) }
    var brightness by rememberSaveable { mutableFloatStateOf(0f) }
    // Intensity is remembered per look while the screen is alive.
    val intensities = remember { mutableStateMapOf<String, Float>() }
    var useFrontCamera by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(CaptureMode.PHOTO) }
    var saving by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordedNanos by remember { mutableLongStateOf(0L) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var focusTap by remember { mutableStateOf<FocusTap?>(null) }

    val effect = remember { FilterCameraEffect() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            // Analysis is off to save bandwidth and power. See useCases() for why this changes by mode.
            setEnabledUseCases(mode.useCases())
            // Slower shutter, but lets the camera use its higher-quality still processing.
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                // Some sensors (e.g. Galaxy A22) list full resolution only as a slower "high resolution"
                // size, which CameraX skips by default and falls back to about 1.5 MP.
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .build()
            // Without this the preview is capped at the display size and is often 4:3, which
            // FILL_CENTER then crops and upscales on tall screens.
            previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            // Preview and video share one stream through the GL shader; stills are rendered offscreen.
            setEffects(setOf(effect))
            // Continuous autofocus is CameraX's default. Taps never reach PreviewView because the
            // swipe overlay sits on top, so tap-to-focus is driven from Compose via focusAt instead.
            isTapToFocusEnabled = false
        }
    }

    DisposableEffect(lifecycleOwner, controller) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            recording?.stop()
            controller.unbind()
            effect.release()
        }
    }

    LaunchedEffect(mode) { controller.setEnabledUseCases(mode.useCases()) }

    LaunchedEffect(useFrontCamera) {
        focusTap = null
        controller.cameraSelector =
            if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    // Bake every look's LUT off the main and GL threads so switching and thumbnails never stall.
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { LutCache.prewarm() } }

    val look = Looks.byId(lookId)
    val settings = FilterSettings(look, intensities[look.id] ?: 1f, brightness)
    // Keyed on the settings value, so any look, intensity or brightness change re-runs this.
    LaunchedEffect(settings) { effect.setSettings(settings) }

    val thumbnails by effect.thumbnails.collectAsStateWithLifecycle()
    val visibleLooks = looksFor(Looks.all, category)

    fun stepLook(delta: Int) {
        val index = visibleLooks.indexOfFirst { it.id == lookId }
        val next = (index + delta).coerceIn(0, visibleLooks.lastIndex)
        lookId = visibleLooks[next].id
    }

    val isRecording = recording != null

    fun tapToFocus(at: Offset) {
        val view = previewView ?: return
        val tap = FocusTap(at)
        val started = controller.focusAt(view, at.x, at.y) { focused ->
            if (focusTap == tap) focusTap = tap.copy(focused = focused)
        }
        if (started) focusTap = tap
    }

    // Fade the ring shortly after the result; the timeout covers a result that never arrives.
    LaunchedEffect(focusTap) {
        val tap = focusTap ?: return@LaunchedEffect
        delay(if (tap.focused == null) 6_000 else 1_000)
        focusTap = null
    }

    fun takePhoto() {
        if (saving) return
        saving = true
        val captureSettings = settings
        scope.launch {
            try {
                val image = controller.takePictureSuspend(ContextCompat.getMainExecutor(context))
                withContext(Dispatchers.Default) {
                    val bitmap = FilteredPhotoSaver.process(image, captureSettings)
                    try {
                        FilteredPhotoSaver.save(context, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                Toast.makeText(context, "Saved to Pictures/CameraFilterApp", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }

    fun toggleRecording() {
        recording?.let {
            it.stop()
            return
        }
        val audio = if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            AudioConfig.create(true)
        } else {
            AudioConfig.AUDIO_DISABLED
        }
        recordedNanos = 0L
        recording = controller.startRecording(
            newVideoOutputOptions(context),
            audio,
            ContextCompat.getMainExecutor(context),
        ) { event ->
            when (event) {
                is VideoRecordEvent.Status -> recordedNanos = event.recordingStats.recordedDurationNanos
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    // Some errors (e.g. app backgrounded) still leave a playable file.
                    val saved = event.outputResults.outputUri != Uri.EMPTY
                    if (event.hasError()) Log.w(TAG, "Recording finalised with error ${event.error}", event.cause)
                    Toast.makeText(
                        context,
                        if (saved) "Saved to Movies/CameraFilterApp" else "Recording failed",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CameraPreview(controller = controller, modifier = Modifier.fillMaxSize(), onView = { previewView = it })
        // Fills the same box as the preview, so tap offsets are already in PreviewView coordinates.
        SwipeArea(
            onPrevious = { stepLook(-1) },
            onNext = { stepLook(1) },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = ::tapToFocus) },
        )
        focusTap?.let { FocusRing(it) }
        LookNameOverlay(look, Modifier.align(Alignment.Center))

        if (isRecording) {
            RecordingTimer(
                nanos = recordedNanos,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(top = 12.dp),
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .systemBarsPadding()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AdjustmentSlider(
                showIntensity = !look.isIdentity,
                intensity = settings.intensity,
                brightness = brightness,
                onIntensity = { intensities[look.id] = it },
                onBrightness = { brightness = it },
            )
            // Looks stay adjustable while recording; changes appear in the video live.
            CategoryTabs(selected = category, onSelect = { category = it })
            Spacer(Modifier.height(4.dp))
            FilterStrip(
                looks = visibleLooks,
                selectedId = lookId,
                thumbnails = thumbnails,
                onSelect = { lookId = it.id },
            )

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    TextButton(
                        onClick = { mode = if (mode == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO },
                        enabled = !saving && !isRecording,
                    ) {
                        Text(if (mode == CaptureMode.PHOTO) "Video" else "Photo", color = Color.White)
                    }
                }
                ShutterButton(
                    mode = mode,
                    busy = saving,
                    recording = isRecording,
                    onClick = { if (mode == CaptureMode.PHOTO) takePhoto() else toggleRecording() },
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { useFrontCamera = !useFrontCamera }, enabled = !saving && !isRecording) {
                        Text(if (useFrontCamera) "Back" else "Front", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(mode: CaptureMode, busy: Boolean, recording: Boolean, onClick: () -> Unit) {
    val fill = when {
        busy -> Color.Gray
        mode == CaptureMode.VIDEO -> Color(0xFFE53935)
        else -> Color.White
    }
    Box(
        Modifier
            .size(72.dp)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (recording) 28.dp else 56.dp)
                .background(fill, if (recording) RoundedCornerShape(6.dp) else CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun FocusRing(tap: FocusTap) {
    val scale = remember(tap.at) { Animatable(1.4f) }
    LaunchedEffect(scale) { scale.animateTo(1f, tween(durationMillis = 200)) }
    val color = when (tap.focused) {
        true -> Color(0xFFFFD54F)
        false -> Color.White.copy(alpha = 0.5f)
        null -> Color.White
    }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(color, radius = 36.dp.toPx() * scale.value, center = tap.at, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun RecordingTimer(nanos: Long, modifier: Modifier = Modifier) {
    val seconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
    Text(
        text = "%02d:%02d".format(seconds / 60, seconds % 60),
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .background(Color(0xFFE53935), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun PermissionRationale(askedOnce: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Camera access is needed to show the live filtered preview.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Grant permission") }
        if (askedOnce) {
            TextButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}

private suspend fun LifecycleCameraController.takePictureSuspend(executor: Executor): ImageProxy =
    suspendCancellableCoroutine { cont ->
        takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                if (cont.isActive) cont.resume(image) else image.close()
            }

            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resumeWithException(exception)
            }
        })
    }
