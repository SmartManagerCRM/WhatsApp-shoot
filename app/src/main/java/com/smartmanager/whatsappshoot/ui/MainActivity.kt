package com.smartmanager.whatsappshoot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.smartmanager.whatsappshoot.R
import com.smartmanager.whatsappshoot.platform.AppGraph
import com.smartmanager.whatsappshoot.platform.LocaleHelper

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        setContent { WssTheme { Root(graph) } }
    }

    override fun onResume() {
        super.onResume()
        AppGraph.get(this).refreshLicense()
    }
}

private data class Destination(val label: Int, val icon: ImageVector)

@Composable
private fun Root(graph: AppGraph) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val destinations = listOf(
        Destination(R.string.nav_home, Icons.Filled.Home),
        Destination(R.string.nav_settings, Icons.Filled.Settings),
        Destination(R.string.nav_license, Icons.Filled.Lock),
        Destination(R.string.nav_privacy, Icons.Filled.Info),
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEachIndexed { i, d ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(d.icon, contentDescription = stringResource(d.label)) }, label = { Text(stringResource(d.label)) })
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner)) {
            when (tab) {
                0 -> HomeScreen(graph, openLicense = { tab = 2 }, openSettings = { tab = 1 })
                1 -> SettingsScreen(graph)
                2 -> LicenseScreen(graph)
                else -> PrivacyScreen()
            }
        }
    }
}
