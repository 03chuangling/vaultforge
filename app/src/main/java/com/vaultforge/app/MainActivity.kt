package com.vaultforge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.server.ServerService
import com.vaultforge.app.ui.AddItemScreen
import com.vaultforge.app.ui.BitwardenScreen
import com.vaultforge.app.ui.Brand
import com.vaultforge.app.ui.BwVaultScreen
import com.vaultforge.app.ui.CardBg
import com.vaultforge.app.ui.FileBrowserScreen
import com.vaultforge.app.ui.ItemDetailScreen
import com.vaultforge.app.ui.LineColor
import com.vaultforge.app.ui.SettingsScreen
import com.vaultforge.app.ui.TerminalScreen
import com.vaultforge.app.ui.Text3
import com.vaultforge.app.ui.VaultBg
import com.vaultforge.app.ui.VaultForgeTheme
import com.vaultforge.app.ui.VaultListScreen

sealed interface Nav {
    /** 底部 Tab 1 · 秘钥仓（原页面） */
    data object List : Nav
    /** 底部 Tab 2 · Bitwarden 密码库（浏览 + 动态验证码） */
    data object BwVault : Nav
    /** 底部 Tab 3 · 设置（Bitwarden 拉取入口） */
    data object Settings : Nav
    /** Bitwarden 拉取页（从设置进入，非 Tab） */
    data object BwPull : Nav
    data object Add : Nav
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
    val isMainTab = nav is Nav.List || nav is Nav.BwVault || nav is Nav.Settings
    Column(
        Modifier
            .fillMaxSize()
            .background(VaultBg)
    ) {
        Box(Modifier.weight(1f)) {
            when (val n = nav) {
                is Nav.List -> VaultListScreen(
                    onOpen = { nav = Nav.Detail(it) },
                    onAdd = { nav = Nav.Add },
                    onOpenSettings = { nav = Nav.Settings },
                )
                is Nav.BwVault -> BwVaultScreen(
                    onOpenSettings = { nav = Nav.Settings },
                )
                is Nav.Settings -> SettingsScreen(
                    onOpenBitwarden = { nav = Nav.BwPull },
                )
                is Nav.BwPull -> BitwardenScreen(
                    onBack = { nav = Nav.Settings },
                )
                is Nav.Add -> AddItemScreen(
                    onBack = { nav = Nav.List },
                    onSaved = { nav = Nav.List },
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
        if (isMainTab) {
            BottomTabBar(
                current = when (nav) {
                    is Nav.List -> 0
                    is Nav.BwVault -> 1
                    else -> 2
                },
                onSelect = { i ->
                    nav = when (i) {
                        0 -> Nav.List
                        1 -> Nav.BwVault
                        else -> Nav.Settings
                    }
                },
            )
        }
    }
}

/** 底部三 Tab 导航栏：秘钥仓 / Bitwarden 密码库 / 设置。 */
@Composable
private fun BottomTabBar(current: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf(
        "秘钥仓" to Icons.Filled.VpnKey,
        "Bitwarden" to Icons.Filled.Security,
        "设置" to Icons.Filled.Settings,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardBg)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LineColor)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { idx, tab ->
                val label = tab.first
                val icon = tab.second
                val selected = current == idx
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(idx) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = if (selected) Brand else Text3,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Brand else Text3,
                    )
                }
            }
        }
    }
}