package com.example.camerafilterapp.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import com.example.camerafilterapp.filter.FilterSettings
import com.example.camerafilterapp.gl.StillRenderer
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Turns a full-resolution capture into a saved photo in the shared Pictures collection.
 *
 * With no look or brightness applied, the camera's own JPEG is written unchanged: no decode,
 * no second compression, and every EXIF field kept. Otherwise the JPEG is decoded, cropped to
 * what the preview showed, run through the same GPU shader as the preview, re-encoded, and given
 * the original capture's EXIF (exposure, lens, date) with its orientation reset.
 *
 * Call off the main thread: full-resolution bitmaps are tens of megabytes.
 */
object FilteredPhotoSaver {

    private const val TAG = "FilteredPhotoSaver"
    // The capture was already JPEG-compressed once by the camera, so keep the re-encode near lossless.
    private const val JPEG_QUALITY = 95
    private const val ALBUM = "CameraFilterApp"

    /** Capture metadata carried over to filtered photos. Orientation and size are set afresh. */
    private val CARRIED_EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
    )

    /** What [capture] keeps after closing the camera's image. */
    private class Capture(val jpeg: ByteArray, val width: Int, val height: Int, val crop: Rect, val rotationDegrees: Int) {
        val isFullFrame get() = crop.left == 0 && crop.top == 0 && crop.width() == width && crop.height() == height
    }

    /** Writes [image] with [settings] applied and returns its MediaStore URI. Closes [image]. */
    fun save(context: Context, image: ImageProxy, settings: FilterSettings): Uri {
        val capture = image.use { capture(it) }
        val unfiltered = settings.look.isIdentity && settings.brightness == 0f
        if (unfiltered && capture.isFullFrame) {
            return insert(context, write = { out -> out.write(capture.jpeg) }) { uri ->
                // Matches CameraX's own file saving: when the HAL didn't rotate the pixels or tag
                // the EXIF, the rotation CameraX reports goes into the tag.
                if (capture.rotationDegrees != 0) {
                    updateExif(context, uri) { exif ->
                        if (exif.rotationDegrees == 0) exif.rotate(capture.rotationDegrees)
                    }
                }
            }
        }

        val bitmap = StillRenderer.render(decode(capture), capture.rotationDegrees, settings)
        try {
            return insert(
                context,
                write = { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "JPEG encode failed" } },
            ) { uri -> copyExif(context, uri, capture.jpeg) }
        } finally {
            bitmap.recycle()
        }
    }

    /** Copies the JPEG out so the camera buffer is returned before the slow processing starts. */
    private fun capture(image: ImageProxy): Capture {
        check(image.format == ImageFormat.JPEG) { "Expected a JPEG capture, got format ${image.format}" }
        val buffer = image.planes[0].buffer.apply { rewind() }
        val jpeg = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return Capture(jpeg, image.width, image.height, Rect(image.cropRect), image.imageInfo.rotationDegrees)
    }

    /**
     * Decodes only the crop rect, which CameraX sets from the preview's viewport when the preview
     * and photo aspect ratios differ. Not rotated; the renderer rotates on the GPU.
     */
    private fun decode(capture: Capture): Bitmap {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = if (capture.isFullFrame) {
            BitmapFactory.decodeByteArray(capture.jpeg, 0, capture.jpeg.size, options)
        } else {
            newRegionDecoder(capture.jpeg).let { decoder ->
                try {
                    decoder.decodeRegion(capture.crop, options)
                } finally {
                    decoder.recycle()
                }
            }
        }
        return checkNotNull(bitmap) { "JPEG decode failed" }
    }

    private fun newRegionDecoder(jpeg: ByteArray): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
        }

    /** Carries capture metadata over; the pixels are already upright, so orientation is normal. */
    private fun copyExif(context: Context, uri: Uri, originalJpeg: ByteArray) {
        val original = ExifInterface(ByteArrayInputStream(originalJpeg))
        updateExif(context, uri) { exif ->
            for (tag in CARRIED_EXIF_TAGS) {
                original.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        }
    }

    /** EXIF is metadata only: a failure here leaves a valid photo, so it isn't fatal. */
    private fun updateExif(context: Context, uri: Uri, edit: (ExifInterface) -> Unit) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                edit(exif)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write EXIF to $uri", e)
        }
    }

    /**
     * Creates a pending MediaStore entry, fills it with [write], runs [finish] (e.g. EXIF edits),
     * then publishes it, so the gallery never indexes a half-written file.
     */
    private fun insert(context: Context, write: (OutputStream) -> Unit, finish: (Uri) -> Unit): Uri {
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri).use { out ->
                checkNotNull(out) { "Could not open output stream" }
                write(out)
            }
            finish(uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
