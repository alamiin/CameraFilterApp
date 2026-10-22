package com.example.camerafilterapp.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.camerafilterapp.filter.FilterSettings
import com.example.camerafilterapp.filter.Look
import com.example.camerafilterapp.filter.LookCategory
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Looks shown for a category tab; null means "All". Original is always first. */
fun looksFor(all: List<Look>, category: LookCategory?): List<Look> =
    if (category == null) all else all.filter { it.isIdentity || it.category == category }

@Composable
fun CategoryTabs(selected: LookCategory?, onSelect: (LookCategory?) -> Unit, modifier: Modifier = Modifier) {
    val tabs = listOf<LookCategory?>(null) + LookCategory.entries
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(tabs, key = { it?.name ?: "all" }) { category ->
            val isSelected = category == selected
            Text(
                text = category?.label ?: "All",
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f),
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable { onSelect(category) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
fun FilterStrip(
    looks: List<Look>,
    selectedId: String,
    thumbnails: Map<String, Bitmap>,
    onSelect: (Look) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId, looks) {
        val index = looks.indexOfFirst { it.id == selectedId }
        if (index >= 0) listState.animateScrollToItem(maxOf(0, index - 2))
    }
    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(looks, key = { it.id }) { look ->
            FilterThumb(
                look = look,
                thumbnail = thumbnails[look.id],
                selected = look.id == selectedId,
                onClick = { onSelect(look) },
            )
        }
    }
}

@Composable
private fun FilterThumb(look: Look, thumbnail: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(64.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(shape)
                .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier)
                .background(PLACEHOLDER_BRUSH),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = remember(thumbnail) { thumbnail.asImageBitmap() },
                    contentDescription = look.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .padding(if (selected) 2.dp else 0.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = look.name,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

enum class Adjustment(val label: String) { INTENSITY("Intensity"), BRIGHTNESS("Brightness") }

/** One slider that edits either the look's intensity or the global brightness. */
@Composable
fun AdjustmentSlider(
    showIntensity: Boolean,
    intensity: Float,
    brightness: Float,
    onIntensity: (Float) -> Unit,
    onBrightness: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var adjustment by remember { mutableStateOf(Adjustment.INTENSITY) }
    val active = if (showIntensity) adjustment else Adjustment.BRIGHTNESS

    Row(modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Adjustment.entries.forEach { option ->
            val enabled = option == Adjustment.BRIGHTNESS || showIntensity
            Text(
                text = option.label,
                color = when {
                    option == active -> Color.White
                    enabled -> Color.White.copy(alpha = 0.5f)
                    else -> Color.White.copy(alpha = 0.2f)
                },
                fontWeight = if (option == active) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable(enabled = enabled) { adjustment = option }
                    .padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
            )
        }
        val sliderColors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
        )
        if (active == Adjustment.INTENSITY) {
            Slider(
                value = intensity,
                onValueChange = onIntensity,
                colors = sliderColors,
                modifier = Modifier.weight(1f),
            )
            ValueLabel("${(intensity * 100).roundToInt()}") { onIntensity(1f) }
        } else {
            Slider(
                value = brightness,
                onValueChange = onBrightness,
                valueRange = FilterSettings.MIN_BRIGHTNESS..FilterSettings.MAX_BRIGHTNESS,
                colors = sliderColors,
                modifier = Modifier.weight(1f),
            )
            ValueLabel("%+d".format((brightness / FilterSettings.MAX_BRIGHTNESS * 100).roundToInt())) { onBrightness(0f) }
        }
    }
}

/** Tap to reset. */
@Composable
private fun ValueLabel(text: String, onReset: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.End,
        modifier = Modifier
            .width(40.dp)
            .clickable(onClick = onReset),
    )
}

/** Large look name that fades in on change, like most camera apps. */
@Composable
fun LookNameOverlay(look: Look, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(look.id) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        visible = true
        delay(900)
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            text = look.name,
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
        )
    }
}

/** Transparent area over the preview: a horizontal swipe moves to the previous or next look. */
@Composable
fun SwipeArea(onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    Box(
        modifier.pointerInput(threshold) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = { if (total > threshold) previous() else if (total < -threshold) next() },
            ) { _, dragAmount -> total += dragAmount }
        }
    )
}

private val PLACEHOLDER_BRUSH = Brush.linearGradient(listOf(Color(0xFF3A3A3A), Color(0xFF1E1E1E)))
