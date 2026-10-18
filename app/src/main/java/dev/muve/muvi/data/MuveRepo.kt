package dev.muve.muvi.data

import android.app.Application
import dev.muve.mobile.Client
import dev.muve.mobile.Mobile
import dev.muve.mobile.Progress
import dev.muve.muvi.TransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Error del SDK: el mensaje llega como "[codigo] texto". */
class MuveError(val code: String, override val message: String) : Exception(message) {
    companion object {
        private val re = Regex("^\\[([a-z_]+)\\]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
        fun from(e: Throwable): MuveError {
            if (e is MuveError) return e
            val m = re.find(e.message ?: "")
            return if (m != null) MuveError(m.groupValues[1], m.groupValues[2]) else MuveError("error", e.message ?: e.toString())
        }
    }

    /** Texto para mostrar en la app. */
    val friendly: String
        get() = when (code) {
            "network" -> "Sin conexión con el servidor."
            "invalid_key", "unauthorized" -> "La llave no es válida. Revisa que sea la completa (botón Copiar del panel)."
            "key_revoked" -> "Esta llave fue revocada. Crea otra en el panel."
            "key_expired" -> "Esta llave ya venció. Crea otra en el panel."
            "wrong_secret" -> "El secreto de cifrado no corresponde a estos archivos."
            "missing_permission" -> "La llave no tiene permiso para esto: $message"
            "file_exists" -> "Ya existe un archivo con ese nombre en esa carpeta."
            "canceled" -> "Cancelado."
            else -> message
        }
}

/** Avance de una tanda de subidas o descargas. bytes/size son del archivo actual. */
data class Transfer(
    val upload: Boolean,
    val index: Int,
    val total: Int,
    val name: String,
    val bytes: Long = 0,
    val size: Long = 0,
    val done: Boolean = false,
    val result: String? = null,
) {
    val active: Boolean get() = !done
}

/** Un archivo elegido en el teléfono para subir (lo abre la UI con el ContentResolver). */
class PickedFile(
    val name: String,
    val size: Long,
    val openFd: () -> Int?,
    val copyTo: (File) -> Unit,
    val release: () -> Unit = {},
)

/**
 * Todo lo que la app necesita del SDK. Una sola instancia (en MuviApp).
 * - [client]: catálogo, reproducir (servidor local de streaming), descargas y borrar.
 * - [uploadClient]: solo subidas, aparte para que cancelar una subida no corte una descarga.
 */
class MuveRepo(private val app: Application) {
    val settings = Settings(app)
    val positions = Positions(app)
    val thumbs = Thumbs(app.filesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogFile = File(app.filesDir, "catalog.json")

    @Volatile private var client: Client? = null
    @Volatile private var uploadClient: Client? = null

    /** Último catálogo conocido (lo usa también el reproductor para los subtítulos). */
    @Volatile var catalog: Catalog = Catalog.EMPTY
        private set

    private val _upload = MutableStateFlow<Transfer?>(null)
    val upload: StateFlow<Transfer?> = _upload
    private var uploadJob: Job? = null

    private val _download = MutableStateFlow<Transfer?>(null)
    val download: StateFlow<Transfer?> = _download
    private var downloadJob: Job? = null
    private val downloadQueue = ArrayDeque<Video>()

    /** Se llama cuando cambia lo que hay en el drive o en el teléfono (para refrescar la UI). */
    @Volatile var onChanged: (() -> Unit)? = null

    fun client(): Client {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }
            val acc = settings.load() ?: throw MuveError("not_configured", "Falta configurar la app.")
            val c = open(acc)
            if (acc.encrypt && acc.salt.isEmpty()) settings.saveSalt(c.salt())
            client = c
            return c
        }
    }

    private fun upClient(): Client {
        uploadClient?.let { return it }
        synchronized(this) {
            uploadClient?.let { return it }
            val acc = settings.load() ?: throw MuveError("not_configured", "Falta configurar la app.")
            return open(acc).also { uploadClient = it }
        }
    }

    private fun open(a: Account): Client {
        val cfg = Mobile.newConfig()
        cfg.setServer(a.server)
        cfg.setAPIKey(a.apiKey)
        cfg.setEncrypt(a.encrypt)
        cfg.setSecret(a.secret)
        cfg.setSalt(a.salt)
        cfg.setMode(a.mode)
        cfg.setDataDir(app.filesDir.absolutePath)
        // los videos pesan: la caché temporal del modo en línea puede crecer más que la de música
        cfg.setCacheMB(4096)
        return try {
            Mobile.open(cfg)
        } catch (e: Exception) {
            throw MuveError.from(e)
        }
    }

    /** Prueba servidor y llave; si todo bien, guarda y deja esa sesión como la activa. */
    suspend fun testAndSave(a: Account) = withContext(Dispatchers.IO) {
        val c = open(a)
        try {
            c.check()
        } catch (e: Exception) {
            c.close()
            throw MuveError.from(e)
        }
        val salt = if (a.encrypt && a.salt.isEmpty()) c.salt() else a.salt
        settings.save(a.copy(salt = salt))
        synchronized(this@MuveRepo) {
            client?.close()
            uploadClient?.close()
            uploadClient = null
            client = c
        }
    }

    fun signOut() {
        cancelDownload()
        cancelUpload()
        synchronized(this) {
            client?.close()
            uploadClient?.close()
            client = null
            uploadClient = null
        }
        settings.clear()
        catalogFile.delete()
        catalog = Catalog.EMPTY
    }

    // ---------- catálogo ----------

    fun cachedCatalog(): Catalog = try {
        if (catalogFile.exists()) parseCatalog(catalogFile.readText()).also { catalog = it } else Catalog.EMPTY
    } catch (e: Exception) {
        Catalog.EMPTY
    }

    /** Pide la lista al servidor y la guarda para abrir rápido (y sin internet) la próxima vez. */
    suspend fun refreshCatalog(): Catalog = withContext(Dispatchers.IO) {
        val json = try {
            client().list("", false)
        } catch (e: Exception) {
            throw MuveError.from(e)
        }
        val c = parseCatalog(json)
        catalogFile.writeText(catalogToJson(c))
        catalog = c
        c
    }

    // ---------- reproducir ----------

    /** Copia local descifrada, si existe (no usa la red). */
    fun localPath(path: String): String? = try {
        client().localPath(path).takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** URL del servidor local del SDK (127.0.0.1): reproduce mientras baja y se puede adelantar. */
    fun streamUrl(path: String): String = try {
        client().streamURL(path)
    } catch (e: Exception) {
        throw MuveError.from(e)
    }

    /** Copia local si existe; si no, streaming. */
    fun playUri(path: String): android.net.Uri =
        localPath(path)?.let { android.net.Uri.fromFile(File(it)) } ?: android.net.Uri.parse(streamUrl(path))

    // ---------- descargas (de una en una, con avance en bytes) ----------

    /** Agrega videos (y sus subtítulos) a la cola de descargas; los que ya están se saltan. */
    fun download(videos: List<Video>) {
        val cat = catalog
        val want = videos.flatMap { listOf(it) + cat.subtitlesFor(it) }.filter { localPath(it.path) == null }
        if (want.isEmpty()) {
            _download.value = Transfer(false, 0, 0, "", done = true, result = "Ya estaba todo en el teléfono.")
            return
        }
        synchronized(downloadQueue) {
            want.forEach { v -> if (downloadQueue.none { it.path == v.path }) downloadQueue.addLast(v) }
        }
        if (downloadJob?.isActive == true) return
        _download.value = Transfer(false, 1, want.size, want.first().fileName)
        TransferService.start(app)
        downloadJob = scope.launch { runDownloads() }
    }

    private fun runDownloads() {
        var ok = 0
        var n = 0
        var lastError: String? = null
        while (true) {
            val v = synchronized(downloadQueue) { downloadQueue.removeFirstOrNull() } ?: break
            n++
            val total = n + synchronized(downloadQueue) { downloadQueue.size }
            _download.value = Transfer(false, n, total, v.fileName, 0, v.size)
            try {
                val local = client().fetch(v.path, object : Progress {
                    override fun onProgress(done: Long, size: Long) {
                        _download.value = Transfer(false, n, total, v.fileName, done, size)
                    }
                })
                if (isVideo(v.path) && local.isNotEmpty()) thumbs.fromFile(v.path, local)
                ok++
            } catch (e: Exception) {
                val err = MuveError.from(e)
                lastError = "${v.fileName}: ${err.friendly}"
                if (err.code == "canceled") {
                    synchronized(downloadQueue) { downloadQueue.clear() }
                    break
                }
            }
            onChanged?.invoke()
        }
        val failed = n - ok
        _download.value = Transfer(false, n, n, "", done = true,
            result = if (lastError?.endsWith("Cancelado.") == true) "Descarga cancelada ($ok listas)."
            else "Descargados: $ok" + if (failed > 0) ", fallaron $failed ($lastError)" else "")
        onChanged?.invoke()
    }

    fun cancelDownload() {
        synchronized(downloadQueue) { downloadQueue.clear() }
        if (downloadJob?.isActive == true) client?.cancel()
    }

    fun clearDownloadState() {
        if (downloadJob?.isActive != true) _download.value = null
    }

    // ---------- subir ----------

    /** Sube los archivos a la carpeta (cifrados si la app cifra). Corre en el servicio de transferencias. */
    fun uploadFiles(files: List<PickedFile>, folder: String) {
        if (files.isEmpty()) return
        if (uploadJob?.isActive == true) {
            files.forEach { it.release() }
            return
        }
        _upload.value = Transfer(true, 1, files.size, files.first().name, 0, files.first().size)
        TransferService.start(app)
        uploadJob = scope.launch {
            var ok = 0
            var lastError: String? = null
            var canceled = false
            for ((i, f) in files.withIndex()) {
                if (canceled) { f.release(); continue }
                _upload.value = Transfer(true, i + 1, files.size, f.name, 0, f.size)
                val progress = object : Progress {
                    override fun onProgress(done: Long, total: Long) {
                        _upload.value = Transfer(true, i + 1, files.size, f.name, done, total)
                    }
                }
                try {
                    uploadOne(f, folder + f.name, progress)
                    ok++
                } catch (e: Exception) {
                    val err = MuveError.from(e)
                    lastError = "${f.name}: ${err.friendly}"
                    if (err.code == "canceled") canceled = true
                } finally {
                    f.release()
                }
            }
            val failed = files.size - ok
            _upload.value = Transfer(true, files.size, files.size, "", done = true,
                result = if (canceled) "Subida cancelada ($ok subidas)."
                else "Subidos: $ok" + if (failed > 0) ", fallaron $failed ($lastError)" else "")
            try { refreshCatalog() } catch (e: Exception) { }
            onChanged?.invoke()
        }
    }

    private fun uploadOne(f: PickedFile, remote: String, progress: Progress) {
        val c = upClient()
        // la miniatura sale del archivo que ya está en el teléfono (no gasta datos después)
        if (isVideo(f.name)) f.openFd()?.let { fd ->
            val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
            try { thumbs.fromFd(remote, pfd.fileDescriptor) } finally { pfd.close() }
        }
        val fd = f.openFd()
        if (fd != null) {
            try {
                c.uploadFd(fd.toLong(), f.name, remote, false, progress)
                return
            } catch (e: Exception) {
                // algunos proveedores (nubes) dan un descriptor que no se puede leer por partes: se copia primero
                if (!(e.message ?: "").contains("no es un archivo normal")) throw e
            }
        }
        val tmp = File(app.cacheDir, "subir-" + System.nanoTime() + "-" + f.name.replace('/', '_'))
        try {
            f.copyTo(tmp)
            c.upload(tmp.absolutePath, remote, false, progress)
        } finally {
            tmp.delete()
        }
    }

    fun cancelUpload() {
        if (uploadJob?.isActive == true) uploadClient?.cancel()
    }

    fun clearUploadState() {
        if (uploadJob?.isActive != true) _upload.value = null
    }

    // ---------- borrar ----------

    /** Manda el video a la papelera del drive (se puede restaurar desde el panel o con muve). */
    suspend fun trash(v: Video) = withContext(Dispatchers.IO) {
        try {
            client().delete(v.path, false)
        } catch (e: Exception) {
            throw MuveError.from(e)
        }
        positions.clear(v.path)
        thumbs.remove(v.path)
    }

    fun removeLocal(paths: List<String>): Int {
        val c = try { client() } catch (e: Exception) { return 0 }
        return paths.count { c.removeLocal(it) }
    }

    /** Archivos y bytes guardados en el teléfono. */
    fun localStats(): Pair<Int, Long> = try {
        val o = JSONObject(client().localStats())
        o.optInt("files") to o.optLong("bytes")
    } catch (e: Exception) {
        0 to 0L
    }

    val sdkVersion: String get() = try { Mobile.version() } catch (e: Throwable) { "?" }
}
