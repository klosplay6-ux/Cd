package com.localaicoder.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private val CODE_MIME = arrayOf(
    "text/*", "application/json", "application/xml", "application/x-php",
    "application/x-yaml", "application/javascript", "application/octet-stream"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }

    // Sistem dosya seçicisi (SAF): izin gerekmez.
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addAttachments(listOf(uri))
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addAttachments(uris)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Local AI Coder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.loadedModelName ?: "Model yüklü değil",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.loadedModelName == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            })
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            "Başlamak için + ile dosya ekleyin veya bir mesaj yazın.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(state.messages, key = { it.id }) { MessageBubble(it) }
            }

            if (state.pending.isNotEmpty()) {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.pending, key = { it.id }) { a ->
                        AttachmentCard(a, onRemove = { vm.removeAttachment(a.id) })
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box {
                    FilledIconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Dosya ekle")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Dosya ekle") }, onClick = {
                            menuOpen = false; single.launch(arrayOf("*/*"))
                        })
                        DropdownMenuItem(text = { Text("Birden fazla dosya seç") }, onClick = {
                            menuOpen = false; multi.launch(arrayOf("*/*"))
                        })
                        DropdownMenuItem(text = { Text("ZIP arşivi ekle") }, onClick = {
                            menuOpen = false
                            single.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                        })
                        DropdownMenuItem(text = { Text("PHAR plugin ekle") }, onClick = {
                            // PHAR için standart MIME türü yok; tüm dosyalar gösterilir, uzantı sonradan doğrulanır.
                            menuOpen = false; single.launch(arrayOf("*/*"))
                        })
                        DropdownMenuItem(text = { Text("Kod dosyası ekle") }, onClick = {
                            menuOpen = false; multi.launch(CODE_MIME)
                        })
                        DropdownMenuItem(text = { Text("Metin dosyası ekle") }, onClick = {
                            menuOpen = false; multi.launch(arrayOf("text/*"))
                        })
                        DropdownMenuItem(
                            text = { Text("Görsel ekle (model desteklemiyor)") },
                            enabled = false, onClick = {}
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = state.input,
                    onValueChange = vm::onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Mesaj yazın…") },
                    maxLines = 5
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = vm::send, enabled = state.canSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    val isUser = m.role == Role.USER
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                m.attachments.forEach {
                    Text("📎 ${it.name} (${formatSize(it.sizeBytes)})", style = MaterialTheme.typography.labelMedium)
                }
                if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AttachmentCard(a: Attachment, onRemove: () -> Unit) {
    val isError = a.status == AttachmentStatus.ERROR
    Card(
        modifier = Modifier.width(220.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text("${a.kind.label} · ${formatSize(a.sizeBytes)}", style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (a.status) {
                        AttachmentStatus.PROCESSING -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        AttachmentStatus.ERROR -> Icon(Icons.Default.Warning, null, Modifier.size(14.dp))
                        else -> Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(a.status.label, style = MaterialTheme.typography.labelSmall)
                }
                if (a.error != null) Text(a.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Kaldır", Modifier.size(16.dp))
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "boyut bilinmiyor"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
