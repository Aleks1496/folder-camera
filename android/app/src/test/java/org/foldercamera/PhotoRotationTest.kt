package org.foldercamera

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.compose.ui.geometry.Rect
import org.foldercamera.ui.FullPhoto
import org.foldercamera.ui.readCropPreview
import org.foldercamera.ui.rotateCrop
import org.foldercamera.ui.rotateCropPreview
import org.foldercamera.ui.writeEditedPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoRotationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun fullSizeJpegAndPreviewRotateInTheSameDirection() {
        val source = sourceImage()
        val preview = readCropPreview(source)
        for ((degrees, expected) in listOf(90 to Color.BLUE, 180 to Color.YELLOW, 270 to Color.GREEN)) {
            val output = folder.newFile()
            writeEditedPhoto(source, output, FullPhoto, degrees)
            val saved = checkNotNull(BitmapFactory.decodeFile(output.path))
            val rotatedPreview = rotateCropPreview(preview, degrees)
            val dimensions = if (degrees == 180) 96 to 64 else 64 to 96
            assertEquals(dimensions, saved.width to saved.height)
            assertEquals(dimensions, rotatedPreview.width to rotatedPreview.height)
            assertColor(expected, saved.getPixel(saved.width / 4, saved.height / 4))
            assertColor(expected, rotatedPreview.getPixel(saved.width / 4, saved.height / 4))
            saved.recycle()
            rotatedPreview.recycle()
        }
        preview.recycle()
    }

    @Test fun manualRotationUsesExifOrientationAndKeepsTheSelectedContent() {
        val source = sourceImage()
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val crop = Rect(0.5f, 0f, 1f, 0.5f)
        val rotatedCrop = rotateCrop(crop, 90)
        assertEquals(Rect(0.5f, 0.5f, 1f, 1f), rotatedCrop)
        assertEquals(crop, rotateCrop(rotatedCrop, -90))
        val output = folder.newFile()
        writeEditedPhoto(source, output, rotatedCrop, 90)
        val saved = checkNotNull(BitmapFactory.decodeFile(output.path))
        assertEquals(48 to 32, saved.width to saved.height)
        assertColor(Color.RED, saved.getPixel(saved.width / 2, saved.height / 2))
        saved.recycle()
    }

    private fun sourceImage(): File {
        val source = folder.newFile("source.jpg")
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val pixels = IntArray(96 * 64) { index -> colors[(index / 96 / 32) * 2 + index % 96 / 48] }
        val bitmap = Bitmap.createBitmap(pixels, 96, 64, Bitmap.Config.ARGB_8888)
        source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        bitmap.recycle()
        return source
    }

    private fun assertColor(expected: Int, actual: Int) {
        assertTrue(abs(Color.red(expected) - Color.red(actual)) < 12)
        assertTrue(abs(Color.green(expected) - Color.green(actual)) < 12)
        assertTrue(abs(Color.blue(expected) - Color.blue(actual)) < 12)
    }
}
