package dev.muve.muvi.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.muve.muvi.MuviApp
import dev.muve.muvi.data.Account
import dev.muve.muvi.data.Catalog
import dev.muve.muvi.data.MuveError
import dev.muve.muvi.data.PickedFile
import dev.muve.muvi.data.Video
import dev.muve.muvi.data.cleanFolder
import dev.muve.muvi.data.isUploadable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Library, Storage, Setup }

class MuviViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as MuviApp).repo

    var configured by mutableStateOf(repo.settings.isConfigured())
        private set
    var screen by mutableStateOf(if (configured) Screen.Library else Screen.Setup)
    var folder by mutableStateOf("") // "" = raiz, "a/b/" = carpeta
    var query by mutableStateOf<String?>(null) // null = sin buscar

    var catalog by mutableStateOf(Catalog.EMPTY)
        private set
    val videos: List<Video> get() = catalog.videos
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    /** Cambia cuando cambian las copias locales o las miniaturas, para volver a pintar. */
    var localVersion by mutableIntStateOf(0)
        private set
    private var localCache = HashMap<String, Boolean>()

    init {
        repo.onChanged = { viewModelScope.launch { catalog = repo.catalog; localChanged() } }
        if (configured) {
            viewModelScope.launch {
                catalog = withContext(Dispatchers.IO) { repo.cachedCatalog() }
                refresh()
            }
        }
    }

    override fun onCleared() {
        repo.onChanged = null
        super.onCleared()
    }

    fun refresh() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                catalog = repo.refreshCatalog()
                localChanged()
            } catch (e: Exception) {
                message = MuveError.from(e).friendly
            } finally {
                loading = false
            }
        }
    }

    /** ¿Hay copia en el teléfono? (no usa la red; se guarda hasta que cambian las copias). */
    fun isLocal(v: Video): Boolean = localCache.getOrPut(v.path) { repo.localPath(v.path) != null }

    fun localChanged() {
        localCache = HashMap()
        localVersion++
    }

    fun upload(files: List<PickedFile>) {
        val (ok, skipped) = files.partition { isUploadable(it.name) }
        skipped.forEach { it.release() }
        if (skipped.isNotEmpty()) {
            message = "Se omitieron ${skipped.size} que no son video ni subtítulos: " + skipped.joinToString { it.name }.take(120)
        }
        if (repo.upload.value?.active == true) {
            ok.forEach { it.release() }
            message = "Espera a que termine la subida en curso."
            return
        }
        repo.uploadFiles(ok, folder)
    }

    fun download(list: List<Video>) = repo.download(list)

    fun removeLocal(list: List<Video>) {
        val cat = catalog
        val n = repo.removeLocal(list.flatMap { listOf(it.path) + cat.subtitlesFor(it).map { s -> s.path } })
        localChanged()
        message = if (list.size == 1) "Se quitó del teléfono (sigue en la nube)." else "Se quitaron $n archivos del teléfono (siguen en la nube)."
    }

    fun trash(v: Video) {
        viewModelScope.launch {
            try {
                repo.trash(v)
                catalog = catalog.copy(videos = catalog.videos - v)
                localChanged()
                message = "\"${v.title}\" se fue a la papelera del drive."
            } catch (e: Exception) {
                message = MuveError.from(e).friendly
            }
        }
    }

    /** Carpeta nueva: en el drive las carpetas son parte de la ruta, así que existe al subir algo. */
    fun newFolder(name: String) {
        val clean = cleanFolder(name)
        if (clean.isNullOrEmpty()) {
            message = "Nombre de carpeta no válido."
            return
        }
        folder += clean
        query = null
    }

    suspend fun saveSetup(a: Account) {
        repo.testAndSave(a)
        configured = true
        screen = Screen.Library
        folder = ""
        localChanged()
        refresh()
    }

    fun signOut() {
        repo.signOut()
        configured = false
        catalog = Catalog.EMPTY
        screen = Screen.Setup
    }

    fun back(): Boolean {
        when {
            screen == Screen.Storage -> screen = Screen.Library
            screen == Screen.Setup && configured -> screen = Screen.Storage
            query != null -> query = null
            folder.isNotEmpty() -> folder = folder.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            else -> return false
        }
        return true
    }
}
