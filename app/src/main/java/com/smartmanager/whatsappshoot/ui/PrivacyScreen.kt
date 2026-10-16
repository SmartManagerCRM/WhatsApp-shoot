package com.smartmanager.whatsappshoot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PrivacyScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Privacy", style = MaterialTheme.typography.headlineSmall)
        Bullet("Everything stays on this phone. The app has no internet permission, no account, no analytics and no cloud service.")
        Bullet("Screenshots are stored in the app's private temporary storage and are deleted when you finish, when you delete them, or after the retention period you chose.")
        Bullet("The accessibility service reads screen element identifiers and, for WhatsApp only, the title of the open conversation. The title is turned into a one-way, per-session hash to help avoid sharing to the wrong chat. Message text, phone numbers and contact lists are never read, stored or logged.")
        Bullet("The app never sends a message. It opens WhatsApp's share screen; you choose the chat and press Send.")
        Bullet("Content that an app marks as protected (for example banking apps) cannot be captured and the app does not try to bypass this.")
        Bullet("Automation only runs after you press Start and stops when you press Stop. While it runs, a notification and a small button in WhatsApp show that it is active.")
        Text("Permissions used", style = MaterialTheme.typography.titleMedium)
        Bullet("Accessibility service: detect WhatsApp / the open conversation, take the screenshot (Android 11+), show the floating button.")
        Bullet("Screen capture (system dialog): alternative way to take the screenshot; granted by you, per session.")
        Bullet("Notifications: show the active indicator and the Attach action.")
        Bullet("Foreground service (media projection): required by Android while screen-capture permission is active.")
    }
}

@Composable
private fun Bullet(text: String) = Text("• $text", style = MaterialTheme.typography.bodyMedium)
