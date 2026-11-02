package com.rachitratnammudaliar.kitchtally.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/** Photos are copied, resized, into the app's private storage. Nothing leaves the phone. */
object PhotoStore {
    private const val MAX_SIZE = 1280

    private fun dir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

    fun file(context: Context, name: String): File? =
        if (name.isBlank()) null else File(dir(context), name).takeIf { it.exists() }

    /** A temporary file the camera app writes into. */
    fun cameraUri(context: Context): Uri {
        val folder = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(folder, "capture.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** Copies and shrinks a picked or captured image. Returns the stored file name. */
    fun save(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalArgumentException("Couldn't open the image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Not an image")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIZE || bounds.outHeight / (sample * 2) >= MAX_SIZE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Couldn't read the image")

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        val scale = minOf(1f, MAX_SIZE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val name = "${UUID.randomUUID()}.jpg"
        File(dir(context), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return name
    }

    fun delete(context: Context, name: String) {
        if (name.isNotBlank()) File(dir(context), name).delete()
    }

    fun load(context: Context, name: String): Bitmap? =
        file(context, name)?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
}

/** Small on-device settings. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("kitchtally", Context.MODE_PRIVATE)

    var currency: String
        get() = prefs.getString("currency", "EUR") ?: "EUR"
        set(value) = prefs.edit().putString("currency", value).apply()
}
