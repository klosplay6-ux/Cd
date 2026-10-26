package com.localaicoder.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localaicoder.attachments.Attachment
import com.localaicoder.attachments.AttachmentKind
import com.localaicoder.attachments.AttachmentManager
import com.localaicoder.attachments.AttachmentStatus
import com.localaicoder.inference.AiChat
import com.localaicoder.inference.InferenceEngine
import com.localaicoder.inference.ModelManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID

enum class Role { USER, ASSISTANT, SYSTEM_NOTICE }

data class ChatMessage(val id: String = UUID.randomUUID().toString(), val role: Role, val text: String, val attachments: List<Attachment> = emptyList())

data class ChatUiState(
    val input: String = "",
    val pending: List<Attachment> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val models: List<ModelManager.ImportedModel> = emptyList(),
    val loadedModelName: String? = null,
    val status: String? = null,
    val busy: Boolean = false,
    val generating: Boolean = false
) {
    val canSend: Boolean get() = (input.isNotBlank() || pending.any { it.status == AttachmentStatus.READY }) && pending.none { it.status == AttachmentStatus.PROCESSING } && !busy
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val MAX_GENERATION_TOKENS = 512
    }

    private val attachmentManager = AttachmentManager(app)
    private val modelManager = ModelManager(app)
    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(app) }
    private val _state = MutableStateFlow(ChatUiState(models = modelManager.list()))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    @Volatile private var stopRequestedByUser = false

    fun onInputChange(text: String) = _state.update { it.copy(input = text) }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val already = _state.value.pending.map { it.uri }.toSet()
        val fresh = uris.filter { it !in already }
        viewModelScope.launch {
            val placeholders = fresh.map { uri -> Attachment(UUID.randomUUID().toString(), uri, uri.lastPathSegment?.substringAfterLast('/') ?: "dosya", "", AttachmentKind.UNKNOWN, -1, AttachmentStatus.PROCESSING) }
            _state.update { it.copy(pending = it.pending + placeholders) }
            val results = fresh.map { async { attachmentManager.inspect(it) } }.awaitAll()
            val ids = placeholders.map { it.id }.toSet()
            _state.update { s -> s.copy(pending = s.pending.filterNot { it.id in ids } + results) }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "GGUF model kopyalanıyor…") }
            try {
                val imported = modelManager.importModel(uri)
                _state.update { it.copy(models = modelManager.list(), busy = false, status = "Model hazır: ${imported.name}") }
                loadModel(imported)
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, status = "Model eklenemedi: ${error.message}") }
            }
        }
    }

    fun loadModel(model: ModelManager.ImportedModel) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "${model.name} yükleniyor…") }
            try {
                engine.loadModel(model.path)
                engine.setSystemPrompt("Sen Türkçe yanıt veren, kısa ve güvenilir bir kodlama asistanısın. Yalnızca kullanıcının son isteğini yanıtla. İstenmedikçe API, bağlantı veya kaynak listesi üretme; önceki yanıtı tekrarlama. Emin değilsen bunu açıkça söyle ve API ya da kod uydurma.")
                _state.update { it.copy(busy = false, loadedModelName = model.name, status = "Model hazır") }
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, status = "Model yüklenemedi: ${error.message ?: "uyumsuz veya bozuk GGUF"}") }
            }
        }
    }

    fun removeAttachment(id: String) = _state.update { s -> s.copy(pending = s.pending.filterNot { it.id == id }) }

    /** Stops the native token loop without discarding the partial assistant response. */
    fun stopGeneration() {
        if (_state.value.generating) {
            stopRequestedByUser = true
            engine.stopGeneration()
            _state.update { it.copy(status = "Yanıt durduruluyor…") }
        }
    }

    fun send() {
        val snapshot = _state.value
        if (!snapshot.canSend || snapshot.loadedModelName == null) {
            if (snapshot.loadedModelName == null) _state.update { it.copy(status = "Önce + menüsünden bir GGUF model ekleyin.") }
            return
        }
        stopRequestedByUser = false
        val ready = snapshot.pending.filter { it.status == AttachmentStatus.READY }
        val userMsg = ChatMessage(role = Role.USER, text = snapshot.input.trim(), attachments = ready)
        _state.update { it.copy(input = "", pending = emptyList(), busy = true, generating = false, messages = it.messages + userMsg + ChatMessage(role = Role.ASSISTANT, text = ""), status = "Ekler hazırlanıyor…") }
        viewModelScope.launch {
            var answer = ""
            var failed = false
            try {
                val attachmentContext = buildString {
                    for (attachment in ready) {
                        val content = attachmentManager.readForPrompt(attachment)
                        append("--- ").append(attachment.name).append(" ---\n")
                        append(content).append("\n")
                    }
                }
                val prompt = buildString {
                    append(snapshot.input.trim())
                    if (attachmentContext.isNotBlank()) {
                        append("\n\nAşağıdaki ek dosyaların gerçek içeriğini incele. Dosyaları çalıştırma; cevapta dosya adını ve mümkünse satırları belirt:\n")
                        append(attachmentContext)
                    }
                }.trim()
                _state.update { it.copy(generating = false, status = "İstek modele aktarılıyor…") }
                var lastProgressChars = 0
                val thinkingStartedAt = System.currentTimeMillis()
                val inferenceStateJob = launch {
                    engine.state.collect { inferenceState ->
                        when (inferenceState) {
                            is InferenceEngine.State.ProcessingUserPrompt ->
                                _state.update { it.copy(generating = true, status = "İstek modele aktarılıyor…") }
                            is InferenceEngine.State.Generating ->
                                _state.update { it.copy(generating = true, status = "AI yanıt üretiyor…") }
                            else -> Unit
                        }
                    }
                }
                val progressJob = launch {
                    while (true) {
                        delay(1_000)
                        if (answer.isBlank()) {
                            val seconds = (System.currentTimeMillis() - thinkingStartedAt) / 1_000
                            _state.update { it.copy(status = "Model ilk yanıtı hazırlıyor… ${seconds} sn") }
                        }
                    }
                }
                try {
                    engine.sendUserPrompt(prompt, MAX_GENERATION_TOKENS).collect { token ->
                        answer += token
                        _state.update { s ->
                            val last = s.messages.lastOrNull()
                            if (last?.role == Role.ASSISTANT) {
                                val status = if (answer.length - lastProgressChars >= 128) {
                                    lastProgressChars = answer.length
                                    "Yanıt oluşturuyor… ${answer.length} karakter"
                                } else s.status
                                s.copy(messages = s.messages.dropLast(1) + last.copy(text = answer), status = status)
                            } else s
                        }
                    }
                } finally {
                    inferenceStateJob.cancel()
                    progressJob.cancel()
                }
                if (answer.isBlank()) {
                    _state.update { s ->
                        val last = s.messages.lastOrNull()
                        val messages = if (last?.role == Role.ASSISTANT && last.text.isBlank()) s.messages.dropLast(1) else s.messages
                        if (stopRequestedByUser) {
                            s.copy(messages = messages, status = "Yanıt durduruldu")
                        } else {
                            s.copy(
                                messages = messages + ChatMessage(
                                    role = Role.SYSTEM_NOTICE,
                                    text = "Model boş yanıt döndürdü. Model/bağlam uyumunu kontrol edip yeniden deneyin."
                                )
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                failed = true
                answer = "Yerel inference hatası: ${error.message ?: "bilinmeyen hata"}"
                _state.update { s -> s.copy(messages = s.messages.dropLast(1) + ChatMessage(role = Role.SYSTEM_NOTICE, text = answer)) }
            }
            _state.update {
                it.copy(
                    busy = false,
                    generating = false,
                    status = when {
                        stopRequestedByUser -> "Yanıt durduruldu"
                        failed -> "Hata; ayrıntı mesajda"
                        answer.isBlank() -> "Model boş yanıt verdi"
                        else -> "Hazır"
                    }
                )
            }
        }
    }

    override fun onCleared() { runCatching { engine.destroy() }; super.onCleared() }
}
