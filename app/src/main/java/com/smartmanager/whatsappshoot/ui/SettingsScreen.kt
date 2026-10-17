package com.smartmanager.whatsappshoot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartmanager.whatsappshoot.R
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.CaptureMethod
import com.smartmanager.whatsappshoot.logic.CaptureMode
import com.smartmanager.whatsappshoot.logic.ImageFormat
import com.smartmanager.whatsappshoot.logic.ImageSource
import com.smartmanager.whatsappshoot.logic.SendBehavior
import com.smartmanager.whatsappshoot.logic.sendsExistingPicture
import com.smartmanager.whatsappshoot.platform.AppGraph
import com.smartmanager.whatsappshoot.platform.LocaleHelper

@Composable
fun SettingsScreen(graph: AppGraph) {
    val ctx = LocalContext.current
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    val upd = graph.settings::update
    var confirmAuto by remember { mutableStateOf(false) }
    var folders by remember(s.screenshotFolders) { mutableStateOf(s.screenshotFolders.joinToString(", ")) }
    var lang by remember { mutableStateOf(LocaleHelper.saved(ctx)) }

    if (confirmAuto) {
        AlertDialog(
            onDismissRequest = { confirmAuto = false },
            title = { Text(stringResource(R.string.s_dlg_title)) },
            text = { Text(stringResource(R.string.s_dlg_text)) },
            confirmButton = { Button(onClick = { upd { it.copy(fullyAutomaticSend = true) }; confirmAuto = false }) { Text(stringResource(R.string.s_dlg_ok)) } },
            dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle(stringResource(R.string.s_title), stringResource(R.string.s_subtitle))

        SectionCard(stringResource(R.string.s_lang_title), stringResource(R.string.s_lang_sub)) {
            ChoiceChips(
                listOf(
                    stringResource(R.string.s_lang_system) to LocaleHelper.SYSTEM,
                    stringResource(R.string.lang_en) to "en",
                    stringResource(R.string.lang_ar) to "ar",
                    stringResource(R.string.lang_fr) to "fr",
                ),
                selected = lang,
            ) { code ->
                if (code != lang) {
                    lang = code
                    LocaleHelper.save(ctx, code)
                    LocaleHelper.findActivity(ctx)?.recreate() // rebuilds the screen in the chosen language (right-to-left for Arabic)
                }
            }
        }

        SectionCard(stringResource(R.string.s_what_title), stringResource(R.string.s_what_sub)) {
            ChoiceChips(
                listOf(
                    stringResource(R.string.s_src_gallery) to ImageSource.WHATSAPP_GALLERY,
                    stringResource(R.string.s_src_folder) to ImageSource.LATEST_SCREENSHOT,
                    stringResource(R.string.s_src_new) to ImageSource.CAPTURE_NEW,
                ),
                selected = s.imageSource,
            ) { m -> upd { it.copy(imageSource = m) } }
            if (s.imageSource == ImageSource.WHATSAPP_GALLERY) {
                Text(stringResource(R.string.s_src_gallery_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.imageSource == ImageSource.LATEST_SCREENSHOT) {
                OutlinedTextField(
                    value = folders,
                    onValueChange = { folders = it; upd { st -> st.copy(screenshotFolders = it.split(',').map { n -> n.trim() }.filter { n -> n.isNotEmpty() }.ifEmpty { listOf("Screenshots") }) } },
                    label = { Text(stringResource(R.string.s_folder_label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Text(stringResource(R.string.s_folder_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (s.sendsExistingPicture) {
            SectionCard(stringResource(R.string.s_when_title), stringResource(R.string.s_when_sub)) {
                ChoiceChips(
                    listOf(
                        stringResource(R.string.s_when_every) to SendBehavior.EVERY_CHAT_OPEN,
                        stringResource(R.string.s_when_once) to SendBehavior.ONCE_PER_CHAT,
                        stringResource(R.string.s_when_new) to SendBehavior.NEW_SCREENSHOT_ONLY,
                    ),
                    selected = s.sendBehavior,
                ) { b -> upd { it.copy(sendBehavior = b) } }
                Text(
                    stringResource(
                        when (s.sendBehavior) {
                            SendBehavior.EVERY_CHAT_OPEN -> R.string.s_when_every_help
                            SendBehavior.ONCE_PER_CHAT -> R.string.s_when_once_help
                            SendBehavior.NEW_SCREENSHOT_ONLY -> R.string.s_when_new_help
                        },
                    ) + " " + stringResource(R.string.s_when_hash),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(stringResource(R.string.s_auto_title), stringResource(R.string.s_auto_sub)) {
                SwitchSetting(stringResource(R.string.s_auto_switch), stringResource(R.string.s_auto_switch_help), s.fullyAutomaticSend) { b ->
                    if (b) confirmAuto = true else upd { it.copy(fullyAutomaticSend = false) }
                }
                if (s.fullyAutomaticSend && s.imageSource == ImageSource.LATEST_SCREENSHOT) {
                    RowDivider()
                    SwitchSetting(stringResource(R.string.s_landing), stringResource(R.string.s_landing_help), s.landingGalleryFirst) { b -> upd { it.copy(landingGalleryFirst = b) } }
                }
            }
        }

        SectionCard(stringResource(R.string.s_cap_title), stringResource(R.string.s_cap_sub)) {
            ChoiceChips(
                listOf(
                    stringResource(R.string.s_mode_auto) to CaptureMode.AUTOMATIC,
                    stringResource(R.string.s_mode_delayed) to CaptureMode.DELAYED,
                    stringResource(R.string.s_mode_manual) to CaptureMode.MANUAL,
                ),
                selected = s.mode,
            ) { m -> upd { it.copy(mode = m) } }
            Text(
                stringResource(
                    when (s.mode) {
                        CaptureMode.AUTOMATIC -> R.string.s_mode_auto_help
                        CaptureMode.DELAYED -> R.string.s_mode_delayed_help
                        CaptureMode.MANUAL -> R.string.s_mode_manual_help
                    },
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.mode == CaptureMode.DELAYED) {
                Text(stringResource(R.string.s_delay, (s.delayMs / 1000.0).toString()))
                Slider(
                    value = s.delayMs.toFloat(),
                    onValueChange = { v -> upd { it.copy(delayMs = (v / 500f).toInt() * 500L) } },
                    valueRange = AutomationSettings.MIN_DELAY_MS.toFloat()..AutomationSettings.MAX_DELAY_MS.toFloat(),
                )
            }
            SwitchSetting(stringResource(R.string.s_auto_capture), null, s.automaticCaptureEnabled) { b -> upd { it.copy(automaticCaptureEnabled = b) } }
            SwitchSetting(stringResource(R.string.s_floating), null, s.floatingControlEnabled) { b -> upd { it.copy(floatingControlEnabled = b) } }
            RowDivider()
            Text(stringResource(R.string.s_format), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf(stringResource(R.string.s_png) to ImageFormat.PNG, stringResource(R.string.s_jpeg) to ImageFormat.JPEG), selected = s.format) { f -> upd { it.copy(format = f) } }
            Text(stringResource(R.string.s_method), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(
                listOf(stringResource(R.string.s_method_auto) to CaptureMethod.AUTO, stringResource(R.string.s_method_projection) to CaptureMethod.MEDIA_PROJECTION),
                selected = s.captureMethod,
            ) { m -> upd { it.copy(captureMethod = m) } }
            Text(stringResource(R.string.s_method_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(stringResource(R.string.s_store_title), stringResource(R.string.s_store_sub)) {
            SwitchSetting(stringResource(R.string.s_del_end), null, s.deleteWhenWorkflowEnds) { b -> upd { it.copy(deleteWhenWorkflowEnds = b) } }
            SwitchSetting(stringResource(R.string.s_del_old), null, s.autoCleanup) { b -> upd { it.copy(autoCleanup = b) } }
            if (s.autoCleanup) {
                ChoiceChips(
                    listOf(stringResource(R.string.s_ret_15) to 15L * 60_000, stringResource(R.string.s_ret_1h) to 3_600_000L, stringResource(R.string.s_ret_24h) to 86_400_000L),
                    selected = s.retentionMs,
                ) { r -> upd { it.copy(retentionMs = r) } }
            }
            OutlinedButton(onClick = { graph.deleteAllScreenshots() }) { Text(stringResource(R.string.s_del_all)) }
        }

        SectionCard(stringResource(R.string.s_exp_title)) {
            SwitchSetting(stringResource(R.string.s_exp_switch), stringResource(R.string.s_exp_help), s.directSelectExperimental) { b -> upd { it.copy(directSelectExperimental = b) } }
        }
    }
}
