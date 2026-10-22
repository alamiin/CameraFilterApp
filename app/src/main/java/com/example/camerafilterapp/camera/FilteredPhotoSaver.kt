package com.example.camerafilterapp.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageProxy
import com.example.camerafilterapp.filter.FilterSettings
import com.example.camerafilterapp.gl.StillRenderer
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Applies the selected look to a captured frame and writes it to the shared Pictures
 * collection. Call off the main thread: full-resolution bitmaps are tens of megabytes.
 */
object FilteredPhotoSaver {

    private const val JPEG_QUALITY = 92
    private const val ALBUM = "CameraFilterApp"

    /** Renders the frame through the GPU filter pipeline, upright. */
    fun process(image: ImageProxy, settings: FilterSettings): Bitmap {
        val rotation = image.imageInfo.rotationDegrees
        val source = image.use { it.toBitmap() }
        return StillRenderer.render(source, rotation, settings)
    }

    fun save(context: Context, bitmap: Bitmap): Uri {
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
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "JPEG encode failed" }
            }
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
