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
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Privacy", "What the app does and does not do")

        SectionCard("Your data stays on this phone") {
            Bullet("The app has no internet permission, no account, no analytics and no cloud service. Nothing is uploaded, ever.")
            Bullet("Screenshots are copied to the app's private temporary storage and removed when you finish, when you delete them, or after the retention time you chose.")
        }

        SectionCard("What the accessibility service reads") {
            Bullet("Screen element identifiers and button labels of WhatsApp, to find the attach button, the gallery and the Send button.")
            Bullet("The title of the open conversation, turned into a one-way hash so the app can tell chats apart. It is never stored or logged as text.")
            Bullet("Gallery album names (for example \"Screenshots\") while it picks the picture. Message text and phone numbers are never read, stored or logged.")
            Bullet("Other apps: only the app's name, to know when WhatsApp is in front.")
        }

        SectionCard("Sending") {
            Bullet("By default the app never sends a message: it opens WhatsApp's share screen and you choose the chat and press Send.")
            Bullet("Only if you turn on \"Fully automatic sending\" in Settings does it work inside the open chat and press Send itself. It sends only a photo, and stops without sending if anything does not match. You can stop it at any time with the Stop button.")
            Bullet("Content that an app marks as protected (for example banking apps) cannot be captured, and the app does not try to bypass this.")
        }

        SectionCard("Permissions") {
            Bullet("Accessibility service: notice the open WhatsApp chat and drive WhatsApp's attach screens.")
            Bullet("Photos: find your newest screenshot. Videos: read only the date of the newest video, to be sure the item sent is a photo. Video content is never opened.")
            Bullet("Notifications: show that automation is active.")
            Bullet("Screen capture (optional): an alternative way to take a new screenshot, granted by you through the system dialog.")
        }
    }
}

@Composable
private fun Bullet(text: String) = Text("•  $text", style = MaterialTheme.typography.bodyMedium)
