package dev.muve.muvi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.muve.muvi.BuildConfig
import dev.muve.muvi.R
import dev.muve.muvi.data.Settings
import dev.muve.muvi.data.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(vm: MuviViewModel) {
    val download by vm.repo.download.collectAsState()
    val upload by vm.repo.upload.collectAsState()
    var stats by remember { mutableStateOf(0 to 0L) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }
    val snack = rememberMessages(vm)
    LaunchedEffect(vm.localVersion, download?.done) { stats = withContext(Dispatchers.IO) { vm.repo.localStats() } }
    val s = vm.repo.settings
    val totalCloud = remember(vm.catalog) { vm.videos.sumOf { it.size } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Descargas y ajustes") },
                navigationIcon = { IconButton(onClick = { vm.screen = Screen.Library }) { Ic(R.drawable.ic_back, "Atrás") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            upload?.let { TransferBar(it, { vm.repo.cancelUpload() }, { vm.repo.clearUploadState() }) }
            download?.let { TransferBar(it, { vm.repo.cancelDownload() }, { vm.repo.clearDownloadState() }) }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("En el teléfono", style = MaterialTheme.typography.titleMedium)
                    Text("${stats.first} archivos · ${humanSize(stats.second)}")
                    Text("En tu drive: ${vm.videos.size} videos · ${humanSize(totalCloud)}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (s.mode == Settings.MODE_LOCAL) "Lo que descargas se queda hasta que lo quites. Lo demás se ve en línea sin guardarse."
                        else "Modo solo en línea: lo descargado va a una caché temporal que se limpia sola.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { confirmClear = true }, enabled = stats.first > 0) { Text("Quitar todo del teléfono") }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cuenta", style = MaterialTheme.typography.titleMedium)
                    Text(s.server, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        (if (s.encrypt) "Cifrado activado" else "Sin cifrar") + " · " +
                            (if (s.mode == Settings.MODE_LOCAL) "guardar en el teléfono" else "solo en línea"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { vm.screen = Screen.Setup }) { Text("Cambiar configuración") }
                    TextButton(onClick = { confirmOut = true }) { Text("Cerrar sesión en este teléfono", color = MaterialTheme.colorScheme.error) }
                }
            }

            Text("muvi ${BuildConfig.VERSION_NAME} · SDK muve ${vm.repo.sdkVersion}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmClear) {
        ConfirmDialog("¿Quitar todo del teléfono?",
            "Se borran las copias locales. Tus videos siguen en la nube y los puedes volver a descargar.", "Quitar",
            { vm.removeLocal(vm.videos) }) { confirmClear = false }
    }
    if (confirmOut) {
        ConfirmDialog("¿Cerrar sesión?",
            "Se borran la llave, el secreto y la lista de este teléfono. Las copias descargadas se quedan hasta que borres los datos de la app.",
            "Cerrar sesión", { vm.signOut() }) { confirmOut = false }
    }
}
