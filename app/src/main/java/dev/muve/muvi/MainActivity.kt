package dev.muve.muvi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.muve.muvi.ui.LibraryScreen
import dev.muve.muvi.ui.MuviViewModel
import dev.muve.muvi.ui.Screen
import dev.muve.muvi.ui.SetupScreen
import dev.muve.muvi.ui.StorageScreen
import dev.muve.muvi.ui.theme.MuviTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MuviTheme {
                val vm: MuviViewModel = viewModel()
                App(vm)
            }
        }
    }
}

@Composable
private fun App(vm: MuviViewModel) {
    val canGoBack = when (vm.screen) {
        Screen.Library -> vm.folder.isNotEmpty() || vm.query != null
        Screen.Setup -> vm.configured
        else -> true
    }
    BackHandler(enabled = canGoBack) { vm.back() }
    val upload by vm.repo.upload.collectAsState()
    val download by vm.repo.download.collectAsState()
    NotificationPermission(upload?.active == true || download?.active == true)

    Surface(Modifier.fillMaxSize()) {
        when (vm.screen) {
            Screen.Setup -> Box(Modifier.safeDrawingPadding()) { SetupScreen(vm) }
            Screen.Library -> LibraryScreen(vm)
            Screen.Storage -> StorageScreen(vm)
        }
    }
}

/** Android 13+: pedir permiso de notificaciones la primera vez que algo se sube o se baja (para ver el avance). */
@Composable
private fun NotificationPermission(transferring: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(transferring) {
        if (transferring && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
