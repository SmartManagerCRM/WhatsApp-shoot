package com.smartmanager.whatsappshoot.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartmanager.whatsappshoot.R
import com.smartmanager.whatsappshoot.logic.Product
import com.smartmanager.whatsappshoot.platform.AppGraph
import com.smartmanager.whatsappshoot.platform.CatalogueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the product dialog is editing: a new product (id null) or an existing one. */
private data class Editing(val product: Product?)

@Composable
fun CatalogueScreen(graph: AppGraph) {
    val store = graph.catalogue
    val products by store.products.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Editing?>(null) }
    var deleting by remember { mutableStateOf<Product?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle(stringResource(R.string.c_title), stringResource(R.string.c_subtitle))

        SectionCard(stringResource(R.string.c_recog_title), stringResource(R.string.c_recog_sub)) {
            SwitchSetting(stringResource(R.string.c_recog_switch), stringResource(R.string.c_recog_switch_help), settings.catalogueEnabled) { b -> graph.settings.update { it.copy(catalogueEnabled = b) } }
            RowDivider()
            SwitchSetting(stringResource(R.string.c_desc_switch), stringResource(R.string.c_desc_switch_help), settings.captionIncludeDescription) { b -> graph.settings.update { it.copy(captionIncludeDescription = b) } }
            if (settings.catalogueEnabled && !settings.fullyAutomaticSend) {
                Text(stringResource(R.string.c_needs_auto), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.c_count, products.size), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { editing = Editing(null) }) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.c_add), Modifier.padding(start = 6.dp))
            }
        }

        if (products.isEmpty()) {
            SectionCard(stringResource(R.string.c_empty_title)) {
                Text(stringResource(R.string.c_empty_text), style = MaterialTheme.typography.bodyMedium)
            }
        }
        products.forEach { p ->
            SectionCard(p.name) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProductPicture(store, p, Modifier.size(84.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (p.price.isNotBlank()) Text(p.price, style = MaterialTheme.typography.titleSmall)
                        if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    }
                    Column {
                        IconButton(onClick = { editing = Editing(p) }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.c_edit)) }
                        IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.c_delete)) }
                    }
                }
            }
        }

        TryItCard(graph)
    }

    editing?.let { e -> ProductDialog(graph, e.product, onClose = { editing = null }) }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.c_delete_title)) },
            text = { Text(stringResource(R.string.c_delete_text, p.name)) },
            confirmButton = { Button(onClick = { store.delete(p.id); deleting = null }) { Text(stringResource(R.string.c_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ProductPicture(store: CatalogueStore, p: Product, modifier: Modifier) {
    val version by store.pictureVersion.collectAsStateWithLifecycle()
    val bmp by produceState<Bitmap?>(null, p.id, version) { value = withContext(Dispatchers.IO) { CatalogueStore.thumbnail(store.imageFile(p), 240) } }
    Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(84.dp)) }
    }
}

@Composable
private fun ProductDialog(graph: AppGraph, product: Product?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = graph.catalogue
    var name by remember { mutableStateOf(product?.name.orEmpty()) }
    var price by remember { mutableStateOf(product?.price.orEmpty()) }
    var description by remember { mutableStateOf(product?.description.orEmpty()) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) { picked = uri; failed = false } }
    val preview by produceState<Bitmap?>(null, picked, product?.id) {
        value = withContext(Dispatchers.IO) {
            picked?.let { CatalogueStore.decode(ctx, it, 480) } ?: product?.let { CatalogueStore.thumbnail(store.imageFile(it), 480) }
        }
    }
    val hasPicture = picked != null || product != null
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(if (product == null) R.string.c_dlg_add_title else R.string.c_dlg_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(160.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(160.dp)) }
                }
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(if (hasPicture) R.string.c_change_photo else R.string.c_choose_photo))
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.c_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text(stringResource(R.string.c_field_price)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.c_field_desc)) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.c_save_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = !busy && name.isNotBlank() && hasPicture, onClick = {
                busy = true
                store.save(product?.id, name, price, description, picked) { ok -> busy = false; if (ok) onClose() else failed = true }
            }) { Text(stringResource(R.string.c_save)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Lets the user check what the phone reads on a photo and which product it would pick, without sending anything. */
@Composable
private fun TryItCard(graph: AppGraph) {
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String?, Product?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true; result = null
            graph.recognizer.test(uri) { text, product -> busy = false; result = text to product }
        }
    }
    SectionCard(stringResource(R.string.c_test_title), stringResource(R.string.c_test_sub)) {
        OutlinedButton(enabled = !busy, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text(stringResource(if (busy) R.string.c_test_busy else R.string.c_test_button))
        }
        result?.let { (text, product) ->
            when {
                text == null -> Text(stringResource(R.string.c_test_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text(if (text.isBlank()) stringResource(R.string.c_test_none) else stringResource(R.string.c_test_text, text.replace(Regex("\\s+"), " ").take(240)), style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (product != null) stringResource(R.string.c_test_match, product.name) else stringResource(R.string.c_test_nomatch),
                        style = MaterialTheme.typography.titleSmall, color = if (product != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
