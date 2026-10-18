package dev.muve.muvi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.muve.muvi.R
import dev.muve.muvi.data.Account
import dev.muve.muvi.data.MuveError
import dev.muve.muvi.data.Settings
import kotlinx.coroutines.launch

private val keyRe = Regex("^dk_[0-9a-f]{16}\\.[0-9a-f]{48}$")

@Composable
fun SetupScreen(vm: MuviViewModel) {
    val s = vm.repo.settings
    val editing = vm.configured
    var server by remember { mutableStateOf(s.server) }
    var key by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var secret2 by remember { mutableStateOf("") }
    var encrypt by remember { mutableStateOf(s.encrypt) }
    var mode by remember { mutableStateOf(s.mode) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        error = null
        val old = if (editing) s.load() else null
        var srv = server.trim().trimEnd('/')
        if (srv.endsWith("/panel")) srv = srv.removeSuffix("/panel")
        if (srv.isNotEmpty() && !srv.contains("://")) srv = "https://$srv"
        val k = key.trim().ifEmpty { old?.apiKey ?: "" }
        val sec = secret.ifEmpty { old?.secret ?: "" }
        error = when {
            srv.isEmpty() -> "Falta la dirección del servidor."
            !keyRe.matches(k) -> "La llave debe ser la completa: dk_ + 16 caracteres + punto + 48 caracteres. En el panel solo se ve el inicio; la completa sale al crearla (botón Copiar)."
            encrypt && sec.length < 8 -> "El secreto debe tener al menos 8 caracteres."
            encrypt && secret.isNotEmpty() && secret != secret2 -> "Los secretos no coinciden."
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            try {
                val salt = if (old != null && old.secret == sec) old.salt else ""
                vm.saveSetup(Account(srv, k, encrypt, if (encrypt) sec else "", mode, salt))
            } catch (e: Exception) {
                error = MuveError.from(e).friendly
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (editing) "Configuración" else "Bienvenido a muvi", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Conecta la app con tu muve drive. Usa una app propia para videos en el panel (por ejemplo muvi) con su llave. Lo que pongas aquí se queda solo en este teléfono; la llave y el secreto se guardan cifrados.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = server, onValueChange = { server = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Servidor") }, placeholder = { Text("https://midominio.com/drive-api") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = { Text("La dirección del panel, sin /panel.") },
        )
        SecretField(key, { key = it }, "Llave de la app",
            if (editing) "Vacío = conservar la actual." else "En el panel: entra a la app, crea una llave para este teléfono y cópiala completa.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cifrar mis archivos")
                Text("Se cifran aquí antes de subir; el servidor nunca ve el contenido.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = encrypt, onCheckedChange = { encrypt = it })
        }
        if (encrypt) {
            SecretField(secret, { secret = it }, "Secreto de cifrado",
                if (editing) "Vacío = conservar el actual." else "El MISMO en todos tus dispositivos. Si lo pierdes, lo cifrado no se recupera.")
            if (secret.isNotEmpty()) SecretField(secret2, { secret2 = it }, "Repite el secreto", null)
        }
        Text("Videos", style = MaterialTheme.typography.titleMedium)
        ModeOption(mode == Settings.MODE_LOCAL, "Guardar en el teléfono",
            "Los que descargues se ven sin internet. Los demás se ven en línea. Recomendado.") { mode = Settings.MODE_LOCAL }
        ModeOption(mode == Settings.MODE_ONLINE, "Solo en línea",
            "Todo desde la nube; lo descargado va a una caché temporal que se limpia sola.") { mode = Settings.MODE_ONLINE }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { submit() }, enabled = !busy) { Text(if (busy) "Probando…" else "Probar y guardar") }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            if (editing && !busy) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.screen = Screen.Storage }) { Text("Cancelar") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, hint: String?) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton(onClick = { show = !show }) { Ic(R.drawable.ic_visibility, if (show) "Ocultar" else "Mostrar") } },
        supportingText = hint?.let { { Text(it) } },
    )
}

@Composable
private fun ModeOption(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}
