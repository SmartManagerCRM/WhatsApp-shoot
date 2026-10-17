package com.smartmanager.whatsappshoot.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartmanager.whatsappshoot.BuildConfig
import com.smartmanager.whatsappshoot.R
import com.smartmanager.whatsappshoot.license.ActivationResult
import com.smartmanager.whatsappshoot.license.ActivationStatus
import com.smartmanager.whatsappshoot.license.LicenseError
import com.smartmanager.whatsappshoot.license.LicensePayload
import com.smartmanager.whatsappshoot.platform.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LicenseScreen(graph: AppGraph) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val status by graph.license.status.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(true) }
    var request by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showEntry by rememberSaveable { mutableStateOf(false) }
    var showRequest by rememberSaveable { mutableStateOf(false) }

    fun activate(text: String) {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.Default) { graph.license.engine.activate(text) }
            messageIsError = r !is ActivationResult.Success && r !is ActivationResult.ControlApplied
            message = when (r) {
                is ActivationResult.Success -> ctx.getString(R.string.license_ok_activated)
                is ActivationResult.ControlApplied -> ctx.getString(if (r.affectsInstalled) R.string.license_ok_stopped else R.string.license_ok_control_stored)
                is ActivationResult.Failure -> ctx.getString(errorRes(r.error))
                ActivationResult.KeystoreUnavailable -> ctx.getString(R.string.license_err_keystore)
                ActivationResult.StorageFailed -> ctx.getString(R.string.license_err_storage)
            }
            withContext(Dispatchers.Default) { graph.license.refresh() }
            if (r is ActivationResult.Success || r is ActivationResult.ControlApplied) { input = ""; showEntry = false }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().take(4096).toByteArray().toString(Charsets.UTF_8) }
                    } catch (e: Exception) { null }
                }
                if (text == null) { messageIsError = true; message = ctx.getString(R.string.license_err_malformed) } else activate(text)
            }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("License", "One license per phone")

        val s = status
        if (!graph.license.keyConfigured) {
            NoticeCard("This build has no license key", stringResource(R.string.license_no_key), Tone.BAD)
        } else {
            LicenseHeader(s)
        }

        message?.let { m -> NoticeCard(if (messageIsError) "Could not use that code" else "Done", m, if (messageIsError) Tone.BAD else Tone.OK) }

        val canEnter = s != null && s !is ActivationStatus.Active
        if (s is ActivationStatus.Active) {
            SectionCard("Renewal or change code", "Received from the seller after you renewed or changed your plan") {
                if (!showEntry) OutlinedButton(onClick = { showEntry = true }) { Text("Enter a code") }
            }
        }
        if (canEnter || showEntry) {
            SectionCard(
                if (s is ActivationStatus.Active) "Enter the code" else "Activate",
                "Paste the license or code you received, or import it from a file",
            ) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it; message = null },
                    label = { Text("License or code") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { activate(input) }, enabled = input.isNotBlank() && !busy) { Text("Activate") }
                    OutlinedButton(onClick = { input = clipboard.getText()?.text.orEmpty() }) { Text("Paste") }
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("From file") }
                }
            }
        }

        if (canEnter) {
            // Normal licenses need no request: paste the code (or import the file) above. A phone-locked license is made only when the seller asks for this code.
            SectionCard("Phone-locked license", "Only if the seller asks for it. Most licenses can be activated directly with the code above.") {
                if (!showRequest) {
                    TextButton(onClick = { showRequest = true }) { Text("The seller asked for an activation request") }
                } else {
                    OutlinedButton(onClick = {
                        scope.launch {
                            request = withContext(Dispatchers.Default) { graph.license.engine.requestCode() }
                            if (request == null) { messageIsError = true; message = ctx.getString(R.string.license_err_keystore) }
                        }
                    }) { Text(if (request == null) "Create activation request" else "Create it again") }
                    request?.let { code ->
                        Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                            Text(code, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy") }
                            OutlinedButton(onClick = {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code), "Send request").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("Share") }
                        }
                        Text("The request holds only a public key for this installation. It is not secret.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        SectionCard("About") {
            StatusRow("App version", BuildConfig.VERSION_NAME)
            Text(
                "The license works offline and belongs to this installation. A new phone, a reinstall or cleared app data needs a new request code and a replacement license from the seller. " +
                    "Normal app updates keep the license. Subscription licenses end on their date unless you enter a renewal code.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The state of the license at a glance: coloured card, what it means, what to do. */
@Composable
private fun LicenseHeader(s: ActivationStatus?) {
    when (s) {
        null -> SectionCard("Checking your license...") { Text("One moment.", style = MaterialTheme.typography.bodyMedium) }
        is ActivationStatus.Active -> {
            val exp = s.license.expiresAtEpochSeconds
            val days = exp?.let { ((it - System.currentTimeMillis() / 1000) / 86_400).toInt() }
            SectionCard("License active") {
                StatusPill(if (days != null && days <= 7) "Renew soon" else "Active", if (days != null && days <= 7) Tone.WARN else Tone.OK)
                StatusRow("Type", if (exp == null) "Lifetime" else "Subscription")
                if (exp != null) StatusRow("Valid until", "${formatDate(exp)} ($days day${if (days == 1) "" else "s"} left)")
                StatusRow("License ID", s.license.maskedId())
                if (s.license.revision > 0) StatusRow("Revision", s.license.revision.toString())
            }
        }
        is ActivationStatus.Expired -> NoticeCard(
            "License expired", "This subscription ended on ${formatDate(s.license.expiresAtEpochSeconds ?: 0L)}. Ask the seller for a renewal code, then enter it below. Automation is paused until then.",
            Tone.BAD,
        ) { Text("License ID ${s.license.maskedId()}", style = MaterialTheme.typography.bodySmall) }
        is ActivationStatus.Revoked -> NoticeCard(
            "License stopped", "The seller stopped this license. Contact them; if it is reinstated they will send you a new code to enter below.",
            Tone.BAD,
        ) { Text("License ID ${s.license.maskedId()}", style = MaterialTheme.typography.bodySmall) }
        ActivationStatus.NotActivated -> NoticeCard("Not activated", stringResource(R.string.license_not_activated_help), Tone.WARN)
        ActivationStatus.RecordCorrupt -> NoticeCard("Activation damaged", stringResource(R.string.license_err_record), Tone.BAD)
        ActivationStatus.InstallKeyMissing -> NoticeCard("Activation no longer valid", stringResource(R.string.license_err_key_missing), Tone.BAD)
        is ActivationStatus.LicenseInvalid -> NoticeCard("License not valid", stringResource(errorRes(s.error)), Tone.BAD)
    }
}

private fun errorRes(e: LicenseError): Int = when (e) {
    LicenseError.MALFORMED -> R.string.license_err_malformed
    LicenseError.BAD_SIGNATURE -> R.string.license_err_signature
    LicenseError.WRONG_PRODUCT -> R.string.license_err_product
    LicenseError.WRONG_INSTALLATION -> R.string.license_err_installation
    LicenseError.INSTALL_KEY_UNAVAILABLE -> R.string.license_err_need_request
    LicenseError.UNSUPPORTED_VERSION, LicenseError.UNSUPPORTED_POLICY, LicenseError.UNSUPPORTED_ENTITLEMENT -> R.string.license_err_unsupported
    LicenseError.PUBLIC_KEY_INVALID -> R.string.license_no_key
    LicenseError.REVOKED -> R.string.license_err_revoked
    LicenseError.SUPERSEDED -> R.string.license_err_superseded
    LicenseError.EXPIRED -> R.string.license_err_expired
}
