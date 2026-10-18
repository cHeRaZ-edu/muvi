package dev.muve.muvi.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest

/**
 * Miniaturas y duración de los videos. Solo se sacan de archivos que ya están en el teléfono
 * (la copia descargada o el archivo que estás subiendo), así nunca gastan datos.
 * Se guardan en filesDir/thumbs/<hash de la ruta>.jpg y .txt (duración en ms).
 */
class Thumbs(filesDir: File) {
    private val dir = File(filesDir, "thumbs").apply { mkdirs() }
    private val mem = LruCache<String, Bitmap>(48)
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>()) // no reintentar en esta sesion

    private fun key(path: String): String =
        MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private fun jpg(path: String) = File(dir, key(path) + ".jpg")
    private fun meta(path: String) = File(dir, key(path) + ".txt")

    fun has(path: String) = jpg(path).exists()

    fun bitmap(path: String): Bitmap? {
        mem.get(path)?.let { return it }
        val f = jpg(path)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)?.also { mem.put(path, it) }
    }

    fun durationMs(path: String): Long = try {
        meta(path).takeIf { it.exists() }?.readText()?.trim()?.toLong() ?: 0L
    } catch (e: Exception) {
        0L
    }

    /** Desde la copia local del video. true si quedó la miniatura. */
    fun fromFile(path: String, local: String): Boolean = path !in failed && extract(path) { it.setDataSource(local) }

    /** Desde un descriptor (archivo elegido para subir). No cierra fd. */
    fun fromFd(path: String, fd: FileDescriptor): Boolean = extract(path) { it.setDataSource(fd) }

    private fun extract(path: String, source: (MediaMetadataRetriever) -> Unit): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            source(r)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur > 0) meta(path).writeText(dur.toString())
            // un cuadro a ~10% del video (el primero suele ser negro)
            val at = if (dur > 0) dur * 1000 / 10 else 1_000_000L
            val frame = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.frameAtTime
                ?: run { failed += path; return false }
            val small = scale(frame, 320)
            jpg(path).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            mem.put(path, small)
            true
        } catch (e: Throwable) {
            failed += path
            false
        } finally {
            try { r.release() } catch (e: Throwable) { }
        }
    }

    fun remove(path: String) {
        mem.remove(path)
        jpg(path).delete()
        meta(path).delete()
    }

    private fun scale(b: Bitmap, maxW: Int): Bitmap {
        if (b.width <= maxW) return b
        val h = (b.height.toLong() * maxW / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, maxW, h, true)
    }
}
