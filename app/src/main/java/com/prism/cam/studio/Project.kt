package com.prism.cam.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.prism.cam.PrismLog
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Projects in one file (`.prismcam` = zip): every scene + the pictures / videos they use. Share it to the PC or another
 * phone (LINE, Drive, Nearby Share, Files…), open it on the other side → the scenes are added (nothing is replaced).
 */
object Project {
    /** pack the whole studio into a .prismcam file and open the share sheet */
    fun share(ctx: Context): Boolean = try {
        val dir = File(ctx.getExternalFilesDir(null), "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val name = "PrismCam-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".prismcam"
        val out = File(dir, name)
        val doc = Studio.toJson()
        val used = Studio.scenes.flatMap { s -> s.layers.map { it.media } }.filter { it.isNotEmpty() }.toSet()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("studio.json")); z.write(doc.toString().toByteArray()); z.closeEntry()
            for (m in used) {
                val f = File(Content.mediaDir, m); if (!f.exists()) continue
                z.putNextEntry(ZipEntry("media/$m")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        PrismLog.i("PROJECT shared ${out.length() / 1024} KB media=${used.size}")
        true
    } catch (t: Throwable) { PrismLog.i("ERR project share ${t.message}"); false }

    /** open a .prismcam: media into the library (names are content hashes, so nothing clashes), scenes appended */
    fun open(ctx: Context, uri: Uri): Int = try {
        var doc: JSONObject? = null
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val n = e.name
                    if (n.contains("..")) continue
                    when {
                        n == "studio.json" -> doc = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                        n.startsWith("media/") && !e.isDirectory -> {
                            val f = File(Content.mediaDir, n.removePrefix("media/").substringAfterLast('/'))
                            if (!f.exists()) f.outputStream().use { z.copyTo(it) }
                        }
                    }
                }
            }
        }
        val d = doc ?: error("no studio.json")
        Studio.appendScenes(d).also { PrismLog.i("PROJECT opened scenes=$it") }
    } catch (t: Throwable) { PrismLog.i("ERR project open ${t.message}"); 0 }
}
