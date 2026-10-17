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
        Bullet("The accessibility service reads screen element identifiers and, for WhatsApp only, the title of the open conversation. The title is turned into a one-way hash to help avoid sharing to the wrong chat. Message text and phone numbers are never read, stored or logged. While the app is choosing the chat in WhatsApp's share list (only when you let it), it looks at the visible names on that screen to find the one matching chat; they are compared as hashes in memory and never stored.")
        Bullet("By default the app never sends a message: it opens WhatsApp's share screen and you choose the chat and press Send. Only if you turn on \"Fully automatic sending\" in Settings may it press Send itself, and only after the chat and the recipient shown by WhatsApp match. You can stop it at any time.")
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
