package com.vaultforge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vaultforge.app.server.ServerService
import com.vaultforge.app.ui.AddItemScreen
import com.vaultforge.app.ui.FileBrowserScreen
import com.vaultforge.app.ui.ItemDetailScreen
import com.vaultforge.app.ui.SettingsScreen
import com.vaultforge.app.ui.TerminalScreen
import com.vaultforge.app.ui.VaultForgeTheme
import com.vaultforge.app.ui.VaultListScreen

sealed interface Nav {
    data object List : Nav
    data object Add : Nav
    data object Settings : Nav
    data class Detail(val id: String) : Nav
    data class Terminal(val id: String, val container: String?) : Nav
    data class Files(val id: String) : Nav
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        runCatching { startForegroundService(Intent(this, ServerService::class.java)) }

        setContent {
            VaultForgeTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    var nav by remember { mutableStateOf<Nav>(Nav.List) }
    when (val n = nav) {
        is Nav.List -> VaultListScreen(
            onOpen = { nav = Nav.Detail(it) },
            onAdd = { nav = Nav.Add },
            onOpenSettings = { nav = Nav.Settings },
        )
        is Nav.Add -> AddItemScreen(
            onBack = { nav = Nav.List },
            onSaved = { nav = Nav.List },
        )
        is Nav.Settings -> SettingsScreen(
            onBack = { nav = Nav.List },
        )
        is Nav.Detail -> ItemDetailScreen(
            itemId = n.id,
            onBack = { nav = Nav.List },
            onOpenTerminal = { c -> nav = Nav.Terminal(n.id, c) },
            onOpenFiles = { nav = Nav.Files(n.id) },
        )
        is Nav.Terminal -> TerminalScreen(
            itemId = n.id,
            container = n.container,
            onBack = { nav = Nav.Detail(n.id) },
        )
        is Nav.Files -> FileBrowserScreen(
            itemId = n.id,
            onBack = { nav = Nav.Detail(n.id) },
        )
    }
}