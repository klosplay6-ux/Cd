package com.localaicoder.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localaicoder.attachments.Attachment
import com.localaicoder.attachments.AttachmentStatus
import com.localaicoder.chat.ChatMessage
import com.localaicoder.chat.ChatViewModel
import com.localaicoder.chat.Role

private val CODE_MIME = arrayOf("text/*", "application/json", "application/xml", "application/x-php", "application/javascript", "application/octet-stream")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.addAttachments(listOf(uri)) } }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addAttachments(it) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importModel) }
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex) }

    Scaffold(topBar = { TopAppBar(title = {
        Column {
            Text("Local AI Coder", style = MaterialTheme.typography.titleMedium)
            Text(state.loadedModelName ?: "Model yüklü değil", style = MaterialTheme.typography.labelSmall,
                color = if (state.loadedModelName == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (state.status != null) Text(state.status!!, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.messages.isEmpty()) item { Text("+ menüsünden GGUF model ekleyin ve ardından yerel sohbeti başlatın.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.messages, key = { it.id }) { MessageBubble(it) }
            }
            if (state.pending.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.pending, key = { it.id }) { a -> AttachmentCard(a) { vm.removeAttachment(a.id) } }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    FilledIconButton(onClick = { menuOpen = true }, enabled = !state.busy) { Icon(Icons.Default.Add, "Ekle") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Add, null) }, text = { Text("GGUF model ekle") }, onClick = { menuOpen = false; modelPicker.launch(arrayOf("application/octet-stream", "*/*")) })
                        state.models.forEach { model -> DropdownMenuItem(text = { Text("Yükle: ${model.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { menuOpen = false; vm.loadModel(model) }) }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Dosya ekle") }, onClick = { menuOpen = false; single.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Birden fazla dosya seç") }, onClick = { menuOpen = false; multi.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("ZIP arşivi ekle") }, onClick = { menuOpen = false; single.launch(arrayOf("application/zip", "application/x-zip-compressed")) })
                        DropdownMenuItem(text = { Text("PHAR plugin ekle") }, onClick = { menuOpen = false; single.launch(arrayOf("application/octet-stream", "application/x-php", "*/*")) })
                        DropdownMenuItem(text = { Text("Kod dosyası ekle") }, onClick = { menuOpen = false; multi.launch(CODE_MIME) })
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(value = state.input, onValueChange = vm::onInputChange, modifier = Modifier.weight(1f), placeholder = { Text("Mesaj yazın…") }, maxLines = 5)
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = if (state.generating) vm::stopGeneration else vm::send,
                    enabled = state.generating || state.canSend
                ) {
                    if (state.generating) Icon(Icons.Default.Stop, "Yanıtı durdur")
                    else Icon(Icons.AutoMirrored.Filled.Send, "Gönder")
                }
            }
        }
    }
}

@Composable private fun MessageBubble(m: ChatMessage) {
    val user = m.role == Role.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = when (m.role) {
            Role.USER -> MaterialTheme.colorScheme.primaryContainer
            Role.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
            Role.SYSTEM_NOTICE -> MaterialTheme.colorScheme.errorContainer
        })) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                m.attachments.forEach { Text("📎 ${it.name} (${formatSize(it.sizeBytes)})", style = MaterialTheme.typography.labelMedium) }
                if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable private fun AttachmentCard(a: Attachment, onRemove: () -> Unit) {
    val error = a.status == AttachmentStatus.ERROR
    Card(Modifier.width(220.dp), colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text("${a.kind.label} · ${formatSize(a.sizeBytes)}", style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) { when (a.status) {
                    AttachmentStatus.PROCESSING -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    AttachmentStatus.ERROR -> Icon(Icons.Default.Warning, null, Modifier.size(14.dp))
                    AttachmentStatus.READY -> Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }; Spacer(Modifier.width(4.dp)); Text(a.status.label, style = MaterialTheme.typography.labelSmall) }
                if (a.error != null) Text(a.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Kaldır", Modifier.size(16.dp)) }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "boyut bilinmiyor"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
