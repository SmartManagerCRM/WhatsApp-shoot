package com.smartmanager.whatsappshoot.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartmanager.whatsappshoot.BuildConfig
import com.smartmanager.whatsappshoot.R
import com.smartmanager.whatsappshoot.license.ActivationResult
import com.smartmanager.whatsappshoot.license.ActivationStatus
import com.smartmanager.whatsappshoot.license.LicenseError
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
    var request by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun activate(text: String) {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.Default) { graph.license.engine.activate(text) }
            message = when (r) {
                is ActivationResult.Success -> null
                is ActivationResult.Failure -> ctx.getString(errorRes(r.error))
                ActivationResult.KeystoreUnavailable -> ctx.getString(R.string.license_err_keystore)
                ActivationResult.StorageFailed -> ctx.getString(R.string.license_err_storage)
            }
            withContext(Dispatchers.Default) { graph.license.refresh() }
            if (r is ActivationResult.Success) input = ""
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
                if (text == null) message = ctx.getString(R.string.license_err_malformed) else activate(text)
            }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("License", style = MaterialTheme.typography.headlineSmall)

        val s = status
        when {
            !graph.license.keyConfigured -> Text(stringResource(R.string.license_no_key), color = MaterialTheme.colorScheme.error)
            s is ActivationStatus.Active -> {
                StatusRow("Status", stringResource(R.string.license_active))
                StatusRow("License ID", s.license.maskedId())
                StatusRow("Entitlement", "Lifetime, one installation")
            }
            s == null -> StatusRow("Status", "Checking...")
            s is ActivationStatus.NotActivated -> StatusRow("Status", stringResource(R.string.license_not_activated))
            s is ActivationStatus.RecordCorrupt -> Text(stringResource(R.string.license_err_record), color = MaterialTheme.colorScheme.error)
            s is ActivationStatus.InstallKeyMissing -> Text(stringResource(R.string.license_err_key_missing), color = MaterialTheme.colorScheme.error)
            s is ActivationStatus.LicenseInvalid -> Text(stringResource(errorRes(s.error)), color = MaterialTheme.colorScheme.error)
        }
        StatusRow("Product version", BuildConfig.VERSION_NAME)

        if (s != null && s !is ActivationStatus.Active) {
            Text("Activate", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; message = null },
                label = { Text("License key") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { activate(input) }, enabled = input.isNotBlank() && !busy) { Text("Activate License") }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import License File") }
                OutlinedButton(onClick = { input = clipboard.getText()?.text.orEmpty() }) { Text("Paste") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("Activation request", style = MaterialTheme.typography.titleMedium)
            Text(
                "Licenses are issued for one specific phone. Create a request code, send it to the seller, then import the license you receive. " +
                    "The code contains only a public key for this installation; it is not secret.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                scope.launch {
                    request = withContext(Dispatchers.Default) { graph.license.engine.requestCode() }
                    if (request == null) message = ctx.getString(R.string.license_err_keystore)
                }
            }) { Text("Create activation request") }
            request?.let { code ->
                Text(code, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy") }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code), "Send request").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Share") }
                }
            }
        }

        Text("Recovery", style = MaterialTheme.typography.titleMedium)
        Text(
            "The license works offline and is bound to this installation. If you replace or lose your phone, reinstall Android, or uninstall the app / clear its data, " +
                "the activation on this phone is gone. Open this screen on the new setup, create an activation request, and contact the seller with your order reference " +
                "and the request code; you will receive a replacement license. Normal app updates keep the license.",
            style = MaterialTheme.typography.bodySmall,
        )
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
}
