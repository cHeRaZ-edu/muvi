package dev.muve.muvi.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.muve.muvi.PlayerActivity
import dev.muve.muvi.R
import dev.muve.muvi.data.Folder
import dev.muve.muvi.data.PickedFile
import dev.muve.muvi.data.Video
import dev.muve.muvi.data.browse
import dev.muve.muvi.data.humanSize
import dev.muve.muvi.data.search
import dev.muve.muvi.data.videosUnder

/** Tipos que ofrece el selector de Android: video, subtítulos y "genéricos" (mkv/avi a veces llegan así). */
private val PICK_TYPES = arrayOf(
    "video/*", "application/x-subrip", "text/vtt", "text/x-ssa", "application/ttml+xml",
    "application/octet-stream", "application/x-matroska", "text/plain",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: MuviViewModel) {
    val download by vm.repo.download.collectAsState()
    val upload by vm.repo.upload.collectAsState()
    val snack = rememberMessages(vm)
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(uris.map { pickedFile(ctx, it) })
    }
    var newFolder by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val lv = vm.localVersion // vuelve a pintar cuando cambian las copias locales

    val searching = vm.query != null
    val (folders, videos) = remember(vm.catalog, vm.folder) { browse(vm.videos, vm.folder) }
    val results = remember(vm.catalog, vm.query) { search(vm.videos, vm.query ?: "") }

    fun play(list: List<Video>, index: Int) {
        ctx.startActivity(PlayerActivity.intent(ctx, list.map { it.path }, index))
    }

    if (newFolder) NameDialog("Nueva carpeta", "", "Crear", { vm.newFolder(it) }) { newFolder = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        val fr = remember { FocusRequester() }
                        TextField(
                            value = vm.query ?: "", onValueChange = { vm.query = it }, singleLine = true,
                            placeholder = { Text("Buscar video o carpeta") },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().focusRequester(fr),
                        )
                        LaunchedEffect(Unit) { fr.requestFocus() }
                    } else {
                        Text(vm.folder.trimEnd('/').substringAfterLast('/').ifEmpty { "muvi" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (searching || vm.folder.isNotEmpty()) IconButton(onClick = { vm.back() }) { Ic(R.drawable.ic_back, "Atrás") }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = { vm.query = null }) { Ic(R.drawable.ic_close, "Cerrar búsqueda") }
                    } else {
                        IconButton(onClick = { vm.query = "" }) { Ic(R.drawable.ic_search, "Buscar") }
                        IconButton(onClick = { vm.refresh() }, enabled = !vm.loading) { Ic(R.drawable.ic_refresh, "Actualizar") }
                        IconButton(onClick = { vm.screen = Screen.Storage }) { Ic(R.drawable.ic_more, "Descargas y ajustes") }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                upload?.let { TransferBar(it, { vm.repo.cancelUpload() }, { vm.repo.clearUploadState() }) }
                download?.let { TransferBar(it, { vm.repo.cancelDownload() }, { vm.repo.clearDownloadState() }) }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searching) {
                VideoList(vm, results, emptyList(), header = null,
                    empty = if ((vm.query ?: "").isBlank()) "Escribe para buscar en todos tus videos." else "Nada coincide con \"${vm.query}\".",
                    onPlay = ::play)
            } else {
                val all = remember(vm.catalog, vm.folder) { videosUnder(vm.videos, vm.folder) }
                VideoList(vm, videos, folders, header = {
                    FolderActions(
                        count = all.size,
                        onPlay = { play(all, 0) },
                        onDownload = { vm.download(all) },
                        onUpload = { picker.launch(PICK_TYPES) },
                        onNewFolder = { newFolder = true },
                        uploading = upload?.active == true,
                    )
                    if (all.isEmpty() && !vm.loading) {
                        Text(
                            if (vm.folder.isEmpty()) "Aún no tienes videos. Crea una carpeta y sube videos con el botón de subir (MP4, MKV, WebM, MOV, AVI…), o desde la PC con muve put."
                            else "Carpeta vacía. Sube videos aquí; en el drive la carpeta existe en cuanto tenga algo.",
                            Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }, empty = "", onPlay = ::play)
            }
        }
    }
}

@Composable
private fun FolderActions(
    count: Int, onPlay: () -> Unit, onDownload: () -> Unit, onUpload: () -> Unit, onNewFolder: () -> Unit, uploading: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = onUpload, enabled = !uploading) { Ic(R.drawable.ic_upload, null, Modifier.size(18.dp)); Text(" Subir") }
        if (count > 0) {
            OutlinedButton(onClick = onPlay) { Ic(R.drawable.ic_play, null, Modifier.size(18.dp)); Text(" Ver todo") }
            IconButton(onClick = onDownload) { Ic(R.drawable.ic_download, "Descargar esta carpeta al teléfono") }
        }
        IconButton(onClick = onNewFolder) { Ic(R.drawable.ic_create_folder, "Nueva carpeta") }
        if (count > 0) Text("$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VideoList(
    vm: MuviViewModel, videos: List<Video>, folders: List<Folder>, header: (@Composable () -> Unit)?, empty: String,
    onPlay: (List<Video>, Int) -> Unit,
) {
    if (videos.isEmpty() && folders.isEmpty() && header == null) {
        if (empty.isNotEmpty()) Empty(empty)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        if (header != null) item { header() }
        items(folders, key = { "d:" + it.path }) { f -> FolderRow(vm, f, onPlay) }
        items(videos, key = { "v:" + it.path }) { v ->
            VideoRow(vm, v, showFolder = vm.query != null) { onPlay(videos, videos.indexOf(v)) }
        }
    }
}

@Composable
private fun FolderRow(vm: MuviViewModel, f: Folder, onPlay: (List<Video>, Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { vm.folder = f.path }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Ic(R.drawable.ic_folder, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (f.count == 1) "1 video" else "${f.count} videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Ic(R.drawable.ic_more, "Opciones de ${f.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val all = videosUnder(vm.videos, f.path)
                DropdownMenuItem(text = { Text("Ver todo") }, onClick = { menu = false; onPlay(all, 0) })
                DropdownMenuItem(text = { Text("Descargar al teléfono (${humanSize(all.sumOf { it.size })})") }, onClick = { menu = false; vm.download(all) })
                DropdownMenuItem(text = { Text("Quitar del teléfono") }, onClick = { menu = false; confirmRemove = true })
            }
        }
    }
    if (confirmRemove) {
        ConfirmDialog("¿Quitar del teléfono?", "Se borran las copias de \"${f.name}\" en este teléfono. Siguen en la nube.", "Quitar",
            { vm.removeLocal(videosUnder(vm.videos, f.path)) }) { confirmRemove = false }
    }
}

@Composable
private fun VideoRow(vm: MuviViewModel, v: Video, showFolder: Boolean, onClick: () -> Unit) {
    val local = vm.isLocal(v)
    var menu by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    val resume = remember(v.path, vm.localVersion) { vm.repo.positions.get(v.path) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        VideoThumb(vm, v, local)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    if (showFolder && v.folder.isNotEmpty()) append(v.folder).append(" · ")
                    append(v.ext.uppercase()).append(" · ").append(humanSize(v.size))
                    if (resume > 0) append(" · a medias")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (v.maybeUnsupported) {
                Text("Puede que el teléfono no lo reproduzca", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Ic(if (local) R.drawable.ic_check else R.drawable.ic_cloud, if (local) "En el teléfono" else "En la nube",
            Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            IconButton(onClick = { menu = true }) { Ic(R.drawable.ic_more, "Opciones de ${v.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (local) {
                    DropdownMenuItem(text = { Text("Quitar del teléfono") }, onClick = { menu = false; vm.removeLocal(listOf(v)) })
                } else {
                    DropdownMenuItem(text = { Text("Descargar al teléfono") }, onClick = { menu = false; vm.download(listOf(v)) })
                }
                if (resume > 0) {
                    DropdownMenuItem(text = { Text("Ver desde el inicio") }, onClick = {
                        menu = false; vm.repo.positions.clear(v.path); vm.localChanged(); onClick()
                    })
                }
                DropdownMenuItem(text = { Text("Mandar a la papelera", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmTrash = true })
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 140.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    if (confirmTrash) {
        ConfirmDialog("¿Mandar a la papelera?", "\"${v.title}\" se quita del drive para todos tus dispositivos. Se puede restaurar desde el panel o con muve mientras siga en la papelera.",
            "Mandar a la papelera", { vm.trash(v) }) { confirmTrash = false }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Archivo elegido con el selector de Android. Se toma el permiso persistente para que la subida
 * siga aunque salgas de la app (corre en el servicio), y se suelta al terminar.
 */
private fun pickedFile(ctx: Context, uri: Uri): PickedFile {
    val cr = ctx.applicationContext.contentResolver
    val persisted = try {
        cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true
    } catch (e: Exception) {
        false
    }
    var name: String? = null
    var size = 0L
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            name = c.getString(0)
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    val finalName = (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video").replace('/', '_')
    return PickedFile(
        name = finalName,
        size = size,
        openFd = { cr.openFileDescriptor(uri, "r")?.detachFd() },
        copyTo = { f -> cr.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: error("no pude leer $finalName") },
        release = {
            if (persisted) try { cr.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        },
    )
}
