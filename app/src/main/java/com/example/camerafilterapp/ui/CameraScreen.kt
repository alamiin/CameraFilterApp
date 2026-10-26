package com.example.camerafilterapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionsManager
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camerafilterapp.camera.CameraPreview
import com.example.camerafilterapp.camera.FilteredPhotoSaver
import com.example.camerafilterapp.camera.PhotoProcessing
import com.example.camerafilterapp.camera.availableProcessing
import com.example.camerafilterapp.camera.await
import com.example.camerafilterapp.camera.focusAt
import com.example.camerafilterapp.camera.loadExtensionsManager
import com.example.camerafilterapp.camera.newVideoOutputOptions
import com.example.camerafilterapp.camera.selectorFor
import com.example.camerafilterapp.filter.FilterSettings
import com.example.camerafilterapp.filter.Look
import com.example.camerafilterapp.filter.LookCategory
import com.example.camerafilterapp.filter.Looks
import com.example.camerafilterapp.gl.FilterCameraEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraScreen"

/**
 * Photo mode binds Preview and ImageCapture, both 4:3 (the sensor's native shape), so the preview
 * shows exactly the frame that is saved, at the sensor's full field of view. The filter attaches to
 * Preview alone ([FilterCameraEffect.previewOnly]), which avoids CameraX's stream-sharing copy pass.
 *
 * Video mode binds Preview and VideoCapture at 16:9, sharing one filtered stream so the recording
 * matches the preview. Image capture is left out: a full-resolution JPEG stream limits the shared
 * stream to the display size on many devices (720p on a Galaxy A22), so this frees it to reach 1080p.
 *
 * Switching mode rebinds the camera.
 */
private enum class CaptureMode(
    val useCases: Int,
    /** Long side over short side of the preview and of what is saved. */
    val aspectRatio: Float,
    val previewResolutionSelector: ResolutionSelector,
) {
    PHOTO(
        CameraController.IMAGE_CAPTURE,
        4f / 3f,
        previewSelector(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY, Size(1440, 1080)),
    ),
    VIDEO(
        CameraController.VIDEO_CAPTURE,
        16f / 9f,
        previewSelector(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY, Size(1920, 1080)),
    ),
}

/**
 * About 1080p in the given shape. Without a bound the preview is capped at the display size and may
 * not match the photo's shape; 1440 x 1080 maps one stream pixel to one screen pixel across a
 * 1080-wide display, so the 4:3 preview isn't upscaled.
 */
private fun previewSelector(aspectRatio: AspectRatioStrategy, bound: Size) =
    ResolutionSelector.Builder()
        .setAspectRatioStrategy(aspectRatio)
        .setResolutionStrategy(ResolutionStrategy(bound, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()

/**
 * Applies [mode] and [selector] with a single rebind: while unbound the controller's setters only
 * record their values, whereas each would otherwise restart the camera on its own.
 */
private fun LifecycleCameraController.bind(
    lifecycleOwner: LifecycleOwner,
    mode: CaptureMode,
    selector: CameraSelector,
    effect: FilterCameraEffect,
) {
    unbind()
    // Exactly one of the two, or the preview would be filtered twice (see FilterCameraEffect).
    setEffects(setOf(if (mode == CaptureMode.VIDEO) effect else effect.previewOnly))
    previewResolutionSelector = mode.previewResolutionSelector
    setEnabledUseCases(mode.useCases)
    cameraSelector = selector
    bindToLifecycle(lifecycleOwner)
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
    // Camera exposure compensation in the camera's steps (see ExposureState); 0 is the camera's metering.
    var exposureIndex by rememberSaveable { mutableIntStateOf(0) }
    var processing by rememberSaveable { mutableStateOf(PhotoProcessing.STANDARD) }
    var extensions by remember { mutableStateOf<ExtensionsManager?>(null) }
    // The bound camera, and a counter that changes on every rebind of it (the info object may not).
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }
    var bindCount by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableStateOf<ZoomState?>(null) }

    val effect = remember { FilterCameraEffect() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            // Slower shutter, but lets the camera use its higher-quality still processing.
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                // About 12 MP, or the next size up. High-megapixel sensors combine pixel groups to
                // reach this size, which is cleaner in low light than their full-size remosaic
                // mode, and filtering a 50-200 MP frame would need several hundred MB.
                .setResolutionStrategy(
                    ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                // Some sensors (e.g. Galaxy A22) list full resolution only as a slower "high resolution"
                // size, which CameraX skips by default and falls back to about 1.5 MP.
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .build()
            // Continuous autofocus is CameraX's default. Taps never reach PreviewView because the
            // swipe overlay sits on top, so tap-to-focus is driven from Compose via focusAt instead.
            isTapToFocusEnabled = false
            // Analysis stays off to save bandwidth and power; use cases and effects are set per mode in bind().
        }
    }

    LaunchedEffect(Unit) { extensions = loadExtensionsManager(context) }

    val baseSelector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    val availableProcessing = remember(extensions, useFrontCamera) { extensions.availableProcessing(baseSelector) }
    // Photo only: with VideoCapture bound, CameraX forces stream sharing inside the extension session.
    val activeProcessing =
        if (mode == CaptureMode.PHOTO && processing in availableProcessing) processing else PhotoProcessing.STANDARD

    DisposableEffect(lifecycleOwner, controller) {
        onDispose {
            recording?.stop()
            controller.unbind()
            effect.release()
        }
    }

    LaunchedEffect(lifecycleOwner, mode, useFrontCamera, activeProcessing) {
        focusTap = null
        try {
            controller.bind(lifecycleOwner, mode, extensions.selectorFor(baseSelector, activeProcessing), effect)
            // On first launch the camera provider is still starting; the controller binds once it's ready.
            controller.initializationFuture.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Camera setup failed for $mode, $activeProcessing", e)
            if (activeProcessing != PhotoProcessing.STANDARD) {
                // The device listed the mode but can't run it with these outputs; fall back to standard.
                Toast.makeText(context, "${activeProcessing.label} isn't available here", Toast.LENGTH_SHORT).show()
                processing = PhotoProcessing.STANDARD
            } else {
                Toast.makeText(context, "Camera unavailable", Toast.LENGTH_SHORT).show()
            }
            return@LaunchedEffect
        }
        cameraInfo = controller.cameraInfo
        bindCount++
    }

    // CameraX can reset exposure compensation when use cases are rebound, so the chosen value is
    // re-applied each time the camera opens: after a mode or lens switch and on return to the app.
    DisposableEffect(cameraInfo, bindCount, exposureIndex, lifecycleOwner) {
        val info = cameraInfo
        val observer = Observer<CameraState> { state ->
            val exposure = info?.exposureState
            if (state.type == CameraState.Type.OPEN && exposure != null && exposure.isExposureCompensationSupported) {
                val range = exposure.exposureCompensationRange
                controller.cameraControl?.setExposureCompensationIndex(exposureIndex.coerceIn(range.lower, range.upper))
            }
        }
        info?.cameraState?.observe(lifecycleOwner, observer)
        onDispose { info?.cameraState?.removeObserver(observer) }
    }

    // The controller's zoom state switches to the new camera on every bind, including binds the
    // controller makes on its own once the camera provider or the preview surface is ready, so it
    // also tells us when cameraInfo changes.
    DisposableEffect(lifecycleOwner, controller) {
        val observer = Observer<ZoomState> {
            zoom = it
            cameraInfo = controller.cameraInfo
        }
        controller.zoomState.observe(lifecycleOwner, observer)
        onDispose { controller.zoomState.removeObserver(observer) }
    }

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
                withContext(Dispatchers.Default) { FilteredPhotoSaver.save(context, image, captureSettings) }
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

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Shaped like the stream, so FILL_CENTER crops nothing and the preview frames exactly what
        // is saved. The view's viewport also sets the capture's crop rect, which then covers it all.
        Box(
            if (landscape) {
                Modifier
                    .align(Alignment.Center)
                    .aspectRatio(mode.aspectRatio, matchHeightConstraintsFirst = true)
            } else {
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .aspectRatio(1f / mode.aspectRatio)
            }
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
        }

        if (mode == CaptureMode.PHOTO && availableProcessing.size > 1) {
            TextButton(
                onClick = {
                    val next = availableProcessing.indexOf(activeProcessing) + 1
                    processing = availableProcessing[next % availableProcessing.size]
                },
                enabled = !saving,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Text(activeProcessing.label, color = Color.White)
            }
        }

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
            zoom?.let { state ->
                ZoomPresets(state, onSelect = { controller.setZoomRatio(it) }, Modifier.padding(bottom = 4.dp))
            }
            AdjustmentSlider(
                showIntensity = !look.isIdentity,
                intensity = settings.intensity,
                brightness = brightness,
                exposure = cameraInfo?.exposureState,
                exposureIndex = exposureIndex,
                onIntensity = { intensities[look.id] = it },
                onBrightness = { brightness = it },
                onExposure = { exposureIndex = it },
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

/**
 * Zoom shortcuts. A preset below 1x appears only where the back camera is a logical multi-camera
 * that exposes its ultra-wide lens to apps; CameraX then switches lenses as the ratio changes.
 */
@Composable
private fun ZoomPresets(zoom: ZoomState, onSelect: (Float) -> Unit, modifier: Modifier = Modifier) {
    val presets = buildList {
        if (zoom.minZoomRatio < 0.95f) add(zoom.minZoomRatio)
        add(1f)
        if (zoom.maxZoomRatio >= 2f) add(2f)
    }
    if (presets.size < 2) return
    val selected = presets.minBy { abs(it - zoom.zoomRatio) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { ratio ->
            val isSelected = ratio == selected
            Text(
                text = if (ratio < 1f) "%.1f×".format(ratio) else "${ratio.roundToInt()}×",
                color = if (isSelected) Color(0xFFFFD54F) else Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .clickable { onSelect(ratio) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
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
