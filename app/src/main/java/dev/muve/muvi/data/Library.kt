package dev.muve.muvi.data

import org.json.JSONArray
import org.json.JSONObject

/** Un archivo del drive. path es la ruta remota completa (ej. "videos/Viajes/playa.mkv"). */
data class Video(
    val path: String,
    val version: Int,
    val size: Long,
    val encrypted: Boolean,
    val updatedAt: String,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val title: String get() = fileName.substringBeforeLast('.', fileName)
    val ext: String get() = fileName.substringAfterLast('.', "").lowercase()
    val folder: String get() = path.substringBeforeLast('/', "")

    /** El teléfono quizá no lo abra (contenedor que ExoPlayer no trae). Se sube igual. */
    val maybeUnsupported: Boolean get() = ext in RISKY

    fun toJson(): JSONObject = JSONObject()
        .put("path", path).put("version", version).put("size", size)
        .put("encrypted", encrypted).put("updated_at", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = Video(
            path = o.getString("path"),
            version = o.optInt("version"),
            size = o.optLong("size"),
            encrypted = o.optBoolean("encrypted"),
            updatedAt = o.optString("updated_at"),
        )
    }
}

/** Contenedores de video que se suben y se muestran. */
val VIDEO_EXT = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "3gp", "3g2", "avi", "ts", "mts", "m2ts", "flv", "mpg", "mpeg",
    "ogv", "wmv", "asf", "vob",
)

/** Contenedores que ExoPlayer no abre o abre a medias: se marcan con aviso. */
val RISKY = setOf("wmv", "asf", "vob", "m2ts", "mts")

/** Subtitulos que van junto al video (mismo nombre): ExoPlayer los muestra. */
val SUB_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml")

private fun extOf(name: String) = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

fun isVideo(path: String) = extOf(path) in VIDEO_EXT
fun isSubtitle(path: String) = extOf(path) in SUB_EXT

/** Lo que se puede subir desde el teléfono. */
fun isUploadable(name: String) = isVideo(name) || isSubtitle(name)

/** Catalogo de la app: videos y subtitulos (los demas archivos se ignoran). */
data class Catalog(val videos: List<Video>, val subtitles: List<Video>) {
    /** Subtitulos de un video: mismos nombre y carpeta ("peli.mkv" -> "peli.srt", "peli.es.srt"). */
    fun subtitlesFor(v: Video): List<Video> {
        val base = v.path.substringBeforeLast('.')
        return subtitles.filter { s ->
            val sb = s.path.substringBeforeLast('.')
            sb == base || (sb.startsWith("$base.") && !sb.substring(base.length + 1).contains('/'))
        }
    }

    companion object {
        val EMPTY = Catalog(emptyList(), emptyList())
    }
}

fun parseCatalog(json: String): Catalog {
    val a = JSONArray(json)
    val all = (0 until a.length()).map { Video.fromJson(a.getJSONObject(it)) }
    return Catalog(
        videos = all.filter { isVideo(it.path) }.sortedBy { it.path.lowercase() },
        subtitles = all.filter { isSubtitle(it.path) },
    )
}

fun catalogToJson(c: Catalog): String =
    JSONArray().apply { (c.videos + c.subtitles).forEach { put(it.toJson()) } }.toString()

/** Idioma del subtitulo por su nombre: "peli.es.srt" -> "es"; "peli.srt" -> null. */
fun subtitleLanguage(video: Video, sub: Video): String? {
    val base = video.path.substringBeforeLast('.')
    val sb = sub.path.substringBeforeLast('.')
    return if (sb.length > base.length + 1) sb.substring(base.length + 1) else null
}

/** Una subcarpeta vista desde la carpeta actual. */
data class Folder(val path: String, val name: String, val count: Int)

/** Contenido directo de una carpeta: subcarpetas y videos. prefix = "" (raiz) o "a/b/". */
fun browse(all: List<Video>, prefix: String): Pair<List<Folder>, List<Video>> {
    val folders = linkedMapOf<String, Int>()
    val videos = mutableListOf<Video>()
    for (v in all) {
        if (!v.path.startsWith(prefix)) continue
        val rest = v.path.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash < 0) videos += v else folders.merge(rest.substring(0, slash), 1, Int::plus)
    }
    val fs = folders.entries.map { (name, n) -> Folder(prefix + name + "/", name, n) }.sortedBy { it.name.lowercase() }
    return fs to videos
}

fun videosUnder(all: List<Video>, prefix: String) = all.filter { it.path.startsWith(prefix) }

/** Busca sin importar mayusculas ni acentos. */
fun search(all: List<Video>, query: String): List<Video> {
    val q = normalize(query.trim())
    if (q.isEmpty()) return emptyList()
    val words = q.split(' ').filter { it.isNotBlank() }
    return all.filter { v -> val p = normalize(v.path); words.all { p.contains(it) } }
}

private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** Carpeta valida para el drive: "" o "a/b/" (sin "..", sin diagonales dobles). null = invalida. */
fun cleanFolder(input: String): String? {
    val parts = input.trim().split('/').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.any { it == "." || it == ".." }) return null
    return if (parts.isEmpty()) "" else parts.joinToString("/") + "/"
}

fun humanSize(n: Long): String {
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}
