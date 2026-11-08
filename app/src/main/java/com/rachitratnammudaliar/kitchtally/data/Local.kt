package com.rachitratnammudaliar.kitchtally.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.LruCache
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
        val bitmap = decode(context, uri) ?: throw IllegalArgumentException("Not an image this phone can read")
        val name = "${UUID.randomUUID()}.jpg"
        val out = File(dir(context), name)
        try {
            out.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IllegalStateException("Couldn't save the image") }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return name
    }

    /** Reads JPEG, PNG, WebP, HEIC, AVIF and GIF, upright and no bigger than [MAX_SIZE]. */
    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val viaDecoder = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val scale = minOf(1f, MAX_SIZE.toFloat() / maxOf(w, h))
                    if (scale < 1f) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                }
            }.getOrNull()
            // ImageDecoder already turns photos the right way up.
            if (viaDecoder != null) return viaDecoder
        }
        return decodeOld(context, uri)
    }

    internal fun decodeOld(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        // Reading only the size always returns null, so the size comes from the options.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIZE || bounds.outHeight / (sample * 2) >= MAX_SIZE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

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
        if (scale == 1f && rotation == 0f) return decoded
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    fun delete(context: Context, name: String) {
        if (name.isNotBlank()) File(dir(context), name).delete()
    }

    fun load(context: Context, name: String): Bitmap? =
        file(context, name)?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }

    /** Small cached copies for lists, so many photos don't use up memory. */
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun thumbnail(context: Context, name: String, sizePx: Int): Bitmap? {
        if (name.isBlank()) return null
        val key = "$name@$sizePx"
        thumbs.get(key)?.let { return it }
        val f = file(context, name) ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { thumbs.put(key, it) }
    }
}

/** Small on-device settings. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("kitchtally", Context.MODE_PRIVATE)

    var currency: String
        get() = prefs.getString("currency", "EUR") ?: "EUR"
        set(value) = prefs.edit().putString("currency", value).apply()

    /** Money on the Weekly tab stays hidden until you ask for it. */
    var showSpending: Boolean
        get() = prefs.getBoolean("show_spending", false)
        set(value) = prefs.edit().putBoolean("show_spending", value).apply()

    /** First day of the week: 1 = Monday ... 7 = Sunday. */
    var weekStartDay: Int
        get() = prefs.getInt("week_start_day", 1).coerceIn(1, 7)
        set(value) = prefs.edit().putInt("week_start_day", value.coerceIn(1, 7)).apply()

    /** The day you pressed "Start new week" (epoch day); 0 = never. Weeks run seven days from it. */
    var weekAnchorDay: Long
        get() = prefs.getLong("week_anchor_day", 0L)
        set(value) = prefs.edit().putLong("week_anchor_day", value).apply()

    /** "Shopping for next week" stays on until this day (the next week's first day), then switches itself off. 0 = off. */
    var forNextWeekUntil: Long
        get() = prefs.getLong("for_next_week_until", 0L)
        set(value) = prefs.edit().putLong("for_next_week_until", value).apply()

    /** Monthly budget in cents; 0 means off. */
    var budgetMinor: Long
        get() = prefs.getLong("budget_minor", 0L)
        set(value) = prefs.edit().putLong("budget_minor", value.coerceAtLeast(0L)).apply()
}
