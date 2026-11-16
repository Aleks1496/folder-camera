package org.foldercamera.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import androidx.compose.ui.geometry.Rect
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

internal val FullPhoto = Rect(0f, 0f, 1f, 1f)

internal fun rotateCrop(crop: Rect, degrees: Int): Rect =
    when (Math.floorMod(degrees, 360)) {
        0 -> crop
        90 -> Rect(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
        180 -> Rect(1f - crop.right, 1f - crop.bottom, 1f - crop.left, 1f - crop.top)
        270 -> Rect(crop.top, 1f - crop.right, crop.bottom, 1f - crop.left)
        else -> error("Expected a multiple of 90 degrees")
    }

private fun orientation(file: File, degrees: Int = 0): Matrix {
    val orientation = ExifInterface(file.path).getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
    )
    return Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setScale(-1f, 1f); postRotate(270f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setScale(-1f, 1f); postRotate(90f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
        if (degrees != 0) postRotate(degrees.toFloat())
    }
}

internal fun rotateCropPreview(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val transform = Matrix().apply { setRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, false)
}

private fun Bitmap.orient(transform: Matrix): Bitmap {
    if (transform.isIdentity) return this
    val result = try {
        Bitmap.createBitmap(this, 0, 0, width, height, transform, false)
    } catch (error: RuntimeException) {
        recycle()
        throw error
    }
    if (result !== this) recycle()
    return result
}

internal fun readCropPreview(file: File): Bitmap {
    val transform = orientation(file)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return checkNotNull(BitmapFactory.decodeFile(file.path, options)).orient(transform)
}

@Suppress("DEPRECATION")
internal fun writeEditedPhoto(source: File, output: File, crop: Rect, degrees: Int) {
    val transform = orientation(source, degrees)
    val decoder = checkNotNull(BitmapRegionDecoder.newInstance(source.path, false))
    val bitmap = try {
        val oriented = RectF(0f, 0f, decoder.width.toFloat(), decoder.height.toFloat())
        transform.mapRect(oriented)
        val selected = RectF(
            oriented.left + crop.left * oriented.width(),
            oriented.top + crop.top * oriented.height(),
            oriented.left + crop.right * oriented.width(),
            oriented.top + crop.bottom * oriented.height(),
        )
        val inverse = Matrix()
        check(transform.invert(inverse))
        inverse.mapRect(selected)
        val left = floor(selected.left).toInt().coerceIn(0, decoder.width - 1)
        val top = floor(selected.top).toInt().coerceIn(0, decoder.height - 1)
        val right = ceil(selected.right).toInt().coerceIn(left + 1, decoder.width)
        val bottom = ceil(selected.bottom).toInt().coerceIn(top + 1, decoder.height)
        checkNotNull(decoder.decodeRegion(android.graphics.Rect(left, top, right, bottom), BitmapFactory.Options()))
    } finally {
        decoder.recycle()
    }.orient(transform)
    try {
        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
    } finally {
        bitmap.recycle()
    }
}
