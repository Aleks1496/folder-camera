package org.foldercamera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foldercamera.R
import java.io.File
import kotlin.math.abs

private const val initialCropInset = 0.05f
private val photoRotations = listOf(0, 90, 180, 270)

@Composable
internal fun PhotoCropScreen(
    file: File,
    saving: Boolean,
    saveFailed: Boolean,
    onCancel: () -> Unit,
    onSave: (Rect, Int) -> Unit,
) {
    var preview by remember(file) { mutableStateOf<Bitmap?>(null) }
    var previewFailed by remember(file) { mutableStateOf(false) }
    var rotation by rememberSaveable(file.path) { mutableIntStateOf(0) }
    var cropAdjusted by rememberSaveable(file.path) { mutableStateOf(false) }
    var left by rememberSaveable(file.path) { mutableFloatStateOf(initialCropInset) }
    var top by rememberSaveable(file.path) { mutableFloatStateOf(initialCropInset) }
    var right by rememberSaveable(file.path) { mutableFloatStateOf(1f - initialCropInset) }
    var bottom by rememberSaveable(file.path) { mutableFloatStateOf(1f - initialCropInset) }
    val crop = if (rotation != 0 && !cropAdjusted) FullPhoto
        else rotateCrop(Rect(left, top, right, bottom), rotation)
    fun setCrop(value: Rect) {
        val original = rotateCrop(value, -rotation)
        left = original.left
        top = original.top
        right = original.right
        bottom = original.bottom
        cropAdjusted = true
    }

    LaunchedEffect(file) {
        try {
            preview = withContext(Dispatchers.IO) { readCropPreview(file) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            previewFailed = true
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.crop_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.crop_help), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val bitmap = preview
                when {
                    bitmap != null -> {
                        val rotated = remember(bitmap, rotation) { rotateCropPreview(bitmap, rotation) }
                        key(rotation) { CropPreview(rotated, crop, !saving, ::setCrop) }
                    }
                    previewFailed -> Text(stringResource(R.string.crop_open_failed), color = MaterialTheme.colorScheme.error)
                    else -> CircularProgressIndicator()
                }
            }
            if (saveFailed) {
                Text(stringResource(R.string.crop_save_failed), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.crop_rotation), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (degrees in photoRotations) {
                    FilterChip(
                        selected = rotation == degrees,
                        onClick = { rotation = degrees },
                        enabled = preview != null && !saving,
                        label = { Text(stringResource(R.string.crop_rotation_degrees, degrees)) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel, enabled = !saving) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onClick = { setCrop(FullPhoto) }, enabled = crop != FullPhoto && !saving) {
                    Text(stringResource(R.string.crop_reset))
                }
            }
            Button(onClick = { onSave(crop, rotation) }, enabled = preview != null && !saving, modifier = Modifier.fillMaxWidth()) {
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.crop_save))
            }
        }
    }
}

@Composable
private fun CropPreview(bitmap: Bitmap, crop: Rect, enabled: Boolean, onCrop: (Rect) -> Unit) {
    val currentCrop by rememberUpdatedState(crop)
    val updateCrop by rememberUpdatedState(onCrop)
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = bitmap.width.toFloat() / bitmap.height
        val width = minOf(maxWidth, maxHeight * aspect)
        val height = width / aspect
        Box(Modifier.size(width, height)) {
            Image(image, stringResource(R.string.crop_image), Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.matchParentSize().pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val hit = 28.dp.toPx()
                var drag = CropDrag.NONE
                var selection = FullPhoto
                detectDragGestures(
                    onDragStart = { position ->
                        selection = currentCrop
                        drag = hitTarget(position, selection, size.width.toFloat(), size.height.toFloat(), hit)
                    },
                    onDrag = { change, delta ->
                        if (drag != CropDrag.NONE) {
                            change.consume()
                            selection = resizeCrop(selection, drag, delta.x / size.width, delta.y / size.height)
                            updateCrop(selection)
                        }
                    },
                )
            }) {
                val l = crop.left * size.width
                val t = crop.top * size.height
                val r = crop.right * size.width
                val b = crop.bottom * size.height
                val shade = Color.Black.copy(alpha = .60f)
                drawRect(shade, size = Size(size.width, t))
                drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                drawRect(shade, Offset(0f, t), Size(l, b - t))
                drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
                drawRect(Color.Black, Offset(l, t), Size(r - l, b - t), style = Stroke(4.dp.toPx()))
                drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                for (corner in listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))) {
                    drawCircle(Color.Black, 7.dp.toPx(), corner)
                    drawCircle(Color.White, 5.dp.toPx(), corner)
                }
            }
        }
    }
}

private enum class CropDrag { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private fun hitTarget(position: Offset, crop: Rect, width: Float, height: Float, radius: Float): CropDrag {
    val left = crop.left * width
    val right = crop.right * width
    val top = crop.top * height
    val bottom = crop.bottom * height
    val x = position.x
    val y = position.y
    val nearLeft = abs(x - left) < abs(x - right)
    val nearTop = abs(y - top) < abs(y - bottom)
    if (abs(x - (if (nearLeft) left else right)) <= radius && abs(y - (if (nearTop) top else bottom)) <= radius) {
        return if (nearTop) {
            if (nearLeft) CropDrag.TOP_LEFT else CropDrag.TOP_RIGHT
        } else {
            if (nearLeft) CropDrag.BOTTOM_LEFT else CropDrag.BOTTOM_RIGHT
        }
    }
    return if (x in left..right && y in top..bottom) CropDrag.MOVE else CropDrag.NONE
}

private fun resizeCrop(crop: Rect, drag: CropDrag, dx: Float, dy: Float): Rect {
    val minimum = .05f
    val left = (crop.left + dx).coerceIn(0f, (crop.right - minimum).coerceAtLeast(0f))
    val right = (crop.right + dx).coerceIn((crop.left + minimum).coerceAtMost(1f), 1f)
    val top = (crop.top + dy).coerceIn(0f, (crop.bottom - minimum).coerceAtLeast(0f))
    val bottom = (crop.bottom + dy).coerceIn((crop.top + minimum).coerceAtMost(1f), 1f)
    return when (drag) {
        CropDrag.NONE -> crop
        CropDrag.MOVE -> crop.translate(dx.coerceIn(-crop.left, 1f - crop.right), dy.coerceIn(-crop.top, 1f - crop.bottom))
        CropDrag.TOP_LEFT -> Rect(left, top, crop.right, crop.bottom)
        CropDrag.TOP_RIGHT -> Rect(crop.left, top, right, crop.bottom)
        CropDrag.BOTTOM_LEFT -> Rect(left, crop.top, crop.right, bottom)
        CropDrag.BOTTOM_RIGHT -> Rect(crop.left, crop.top, right, bottom)
    }
}
