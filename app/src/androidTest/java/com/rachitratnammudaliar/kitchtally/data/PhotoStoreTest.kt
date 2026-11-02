package com.rachitratnammudaliar.kitchtally.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Photos chosen from the gallery or taken with the camera must be saved. */
@RunWith(AndroidJUnit4::class)
class PhotoStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun picture(name: String, format: Bitmap.CompressFormat, width: Int = 3000, height: Int = 2000): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(format, 90, it) }
        return file
    }

    private fun assertSaved(uri: Uri) {
        val name = PhotoStore.save(context, uri)
        val stored = PhotoStore.file(context, name)
        assertNotNull("photo file was not written", stored)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(stored!!.absolutePath, bounds)
        assertEquals(1280, maxOf(bounds.outWidth, bounds.outHeight))
        assertNotNull(PhotoStore.thumbnail(context, name, 96))
        PhotoStore.delete(context, name)
    }

    @Test
    fun savesJpegPngAndWebp() {
        @Suppress("DEPRECATION")
        val webp = Bitmap.CompressFormat.WEBP
        listOf("a.jpg" to Bitmap.CompressFormat.JPEG, "b.png" to Bitmap.CompressFormat.PNG, "c.webp" to webp).forEach { (name, format) ->
            assertSaved(Uri.fromFile(picture(name, format)))
        }
    }

    @Test
    fun savesCameraPhoto() {
        val uri = PhotoStore.cameraUri(context)
        val target = File(File(context.cacheDir, "camera"), "capture.jpg")
        picture("tmp.jpg", Bitmap.CompressFormat.JPEG).copyTo(target, overwrite = true)
        assertSaved(uri)
    }

    @Test
    fun oldPhonesReadPhotosToo() {
        val bitmap = PhotoStore.decodeOld(context, Uri.fromFile(picture("old.jpg", Bitmap.CompressFormat.JPEG)))
        assertNotNull(bitmap)
        assertEquals(1280, maxOf(bitmap!!.width, bitmap.height))
    }

    @Test
    fun smallPhotosKeepTheirSize() {
        val name = PhotoStore.save(context, Uri.fromFile(picture("small.png", Bitmap.CompressFormat.PNG, 400, 300)))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(PhotoStore.file(context, name)!!.absolutePath, bounds)
        assertEquals(400, bounds.outWidth)
        PhotoStore.delete(context, name)
    }

    @Test
    fun rejectsFilesThatAreNotImages() {
        val file = File(context.cacheDir, "notes.txt").apply { writeText("not a picture") }
        try {
            PhotoStore.save(context, Uri.fromFile(file))
            fail("a text file was accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
