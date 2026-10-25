package com.localaicoder.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localaicoder.attachments.Attachment
import com.localaicoder.attachments.AttachmentManager
import com.localaicoder.attachments.AttachmentStatus
import com.localaicoder.attachments.AttachmentKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class Role { USER, SYSTEM_NOTICE }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList()
)

data class ChatUiState(
    val input: String = "",
    val pending: List<Attachment> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    /** Aşama 2'de ModelManager'a bağlanacak; şu an hiçbir model yüklü değil. */
    val loadedModelName: String? = null
) {
    val canSend: Boolean
        get() = (input.isNotBlank() || pending.any { it.status == AttachmentStatus.READY }) &&
            pending.none { it.status == AttachmentStatus.PROCESSING }
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val attachmentManager = AttachmentManager(app)
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    fun onInputChange(text: String) = _state.update { it.copy(input = text) }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val already = _state.value.pending.map { it.uri }.toSet()
        val fresh = uris.filter { it !in already }
        viewModelScope.launch {
            // Önce "işleniyor" kartlarını göster, sonra sonuçla değiştir.
            val placeholders = fresh.map { uri ->
                Attachment(
                    id = UUID.randomUUID().toString(), uri = uri,
                    name = uri.lastPathSegment?.substringAfterLast('/') ?: "dosya",
                    extension = "", kind = AttachmentKind.UNKNOWN, sizeBytes = -1,
                    status = AttachmentStatus.PROCESSING
                )
            }
            _state.update { it.copy(pending = it.pending + placeholders) }
            val results = fresh.map { async { attachmentManager.inspect(it) } }.awaitAll()
            _state.update { s ->
                val ph = placeholders.map { it.id }.toSet()
                s.copy(pending = s.pending.filter { it.id !in ph } + results)
            }
        }
    }

    fun removeAttachment(id: String) =
        _state.update { s -> s.copy(pending = s.pending.filterNot { it.id == id }) }

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        val ready = s.pending.filter { it.status == AttachmentStatus.READY }
        val userMsg = ChatMessage(role = Role.USER, text = s.input.trim(), attachments = ready)
        val notice = if (s.loadedModelName == null) listOf(
            ChatMessage(
                role = Role.SYSTEM_NOTICE,
                text = "Yanıt üretilemedi: yüklü bir GGUF modeli yok. Yerel çıkarım motoru ve model yöneticisi Aşama 2'de eklenecek."
            )
        ) else emptyList()
        _state.update {
            it.copy(input = "", pending = emptyList(), messages = it.messages + userMsg + notice)
        }
    }
}
