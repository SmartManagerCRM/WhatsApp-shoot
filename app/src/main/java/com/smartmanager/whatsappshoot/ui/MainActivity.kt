package com.smartmanager.whatsappshoot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.smartmanager.whatsappshoot.platform.AppGraph

class MainActivity : ComponentActivity() {
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

@Composable
fun WssTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF4FD1B5)) else lightColorScheme(primary = Color(0xFF075E54))
    MaterialTheme(colorScheme = scheme) { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content) }
}

@Composable
private fun Root(graph: AppGraph) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Home", "Settings", "License", "Privacy")
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> HomeScreen(graph, openLicense = { tab = 2 })
            1 -> SettingsScreen(graph)
            2 -> LicenseScreen(graph)
            else -> PrivacyScreen()
        }
    }
}
