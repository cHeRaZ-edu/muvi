package dev.muve.muvi.ui

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.muve.muvi.R
import dev.muve.muvi.data.Transfer
import dev.muve.muvi.data.Video
import dev.muve.muvi.data.fmtTime
import dev.muve.muvi.data.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Ic(@DrawableRes id: Int, desc: String?, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Icon(painterResource(id), contentDescription = desc, modifier = modifier, tint = tint)
}

/**
 * Miniatura 16:9 con la duración encima. Si el video está en el teléfono y aún no tiene miniatura,
 * la saca de la copia local (sin internet).
 */
@Composable
fun VideoThumb(vm: MuviViewModel, v: Video, local: Boolean) {
    val version = vm.localVersion
    val state by produceState<Pair<Bitmap?, Long>>(null to 0L, v.path, version, local) {
        value = withContext(Dispatchers.IO) {
            val t = vm.repo.thumbs
            if (!t.has(v.path) && local) vm.repo.localPath(v.path)?.let { t.fromFile(v.path, it) }
            t.bitmap(v.path) to t.durationMs(v.path)
        }
    }
    val (bmp, dur) = state
    Box(
        Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Ic(R.drawable.ic_movie, null, Modifier.size(28.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dur > 0) {
            Text(
                fmtTime(dur), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun TransferBar(t: Transfer, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val color = if (t.upload) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    Surface(color = color, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (t.done) t.result ?: "Listo"
                    else (if (t.upload) "Subiendo" else "Descargando") + " ${t.index} de ${t.total}: ${t.name}",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (t.done) TextButton(onClick = onDismiss) { Text("OK") } else TextButton(onClick = onCancel) { Text("Cancelar") }
            }
            if (!t.done) {
                if (t.size > 0) {
                    LinearProgressIndicator(progress = { (t.bytes.toFloat() / t.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${humanSize(t.bytes)} de ${humanSize(t.size)}", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Muestra los avisos del ViewModel como snackbar. */
@Composable
fun rememberMessages(vm: MuviViewModel): SnackbarHostState {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }
    return snack
}

/** Pedir un nombre (carpeta nueva). */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nombre") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name); onDismiss() }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Confirmación sencilla. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
