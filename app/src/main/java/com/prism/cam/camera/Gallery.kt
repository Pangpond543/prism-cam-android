package com.prism.cam.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where photos and videos go: Pictures/Prism Cam and Movies/Prism Cam (the phone's gallery shows them as the
 * "Prism Cam" album). Android 10+: MediaStore RELATIVE_PATH, no storage permission. Android 8–9: a real file path
 * (WRITE_EXTERNAL_STORAGE, asked together with the camera).
 */
object Gallery {
    const val ALBUM = "Prism Cam"
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun newName(): String = "PrismCam_" + stamp.format(Date())

    fun photoValues(name: String): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        else legacyPath(Environment.DIRECTORY_PICTURES, "$name.jpg")?.let { put(MediaStore.MediaColumns.DATA, it) }
    }

    fun videoValues(name: String): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
        else legacyPath(Environment.DIRECTORY_MOVIES, "$name.mp4")?.let { put(MediaStore.MediaColumns.DATA, it) }
    }

    @Suppress("DEPRECATION")
    private fun legacyPath(dir: String, file: String): String? = try {
        val d = File(Environment.getExternalStoragePublicDirectory(dir), ALBUM)
        d.mkdirs()
        File(d, file).absolutePath
    } catch (_: Throwable) { null }

    /** Save a finished picture (effects path) as JPEG 95 into the album. Pending until fully written (Android 10+). */
    fun saveJpeg(ctx: Context, bmp: Bitmap, name: String): Uri? {
        val values = photoValues(name).apply {
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            if (Build.VERSION.SDK_INT >= 29) r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (t: Throwable) {
            r.delete(uri, null, null); null
        }
    }

    data class Item(val uri: Uri, val video: Boolean, val added: Long)

    /** Newest photo or video in our album (Android 10+; our own files need no permission). */
    fun latest(ctx: Context): Item? {
        if (Build.VERSION.SDK_INT < 29) return null
        fun q(base: Uri, dir: String, video: Boolean): Item? = try {
            ctx.contentResolver.query(
                base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$dir/$ALBUM%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c -> if (c.moveToFirst()) Item(ContentUris.withAppendedId(base, c.getLong(0)), video, c.getLong(1)) else null }
        } catch (_: Throwable) { null }
        val p = q(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, false)
        val v = q(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES, true)
        return listOfNotNull(p, v).maxByOrNull { it.added }
    }

    /** Small square-ish thumbnail for the last-photo circle. Runs on a background thread. */
    fun thumbnail(ctx: Context, uri: Uri, video: Boolean, px: Int = 192): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
        else if (video) MediaMetadataRetriever().run {
            try { setDataSource(ctx, uri); getFrameAtTime(0)?.let { Bitmap.createScaledBitmap(it, px, px * it.height / it.width.coerceAtLeast(1), true) } } finally { release() }
        } else ctx.contentResolver.openInputStream(uri)?.use { s ->
            BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 16 })
        }
    } catch (_: Throwable) { null }
}
