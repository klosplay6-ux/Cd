package com.localaicoder.attachments

import android.net.Uri

enum class AttachmentKind(val label: String) {
    PHAR("PHAR plugin"),
    ZIP("ZIP arşivi"),
    SOURCE("Kaynak kodu"),
    CONFIG("Yapılandırma"),
    TEXT("Metin"),
    IMAGE("Görsel"),
    UNKNOWN("Bilinmeyen tür")
}

enum class AttachmentStatus(val label: String) {
    PROCESSING("İşleniyor"),
    READY("Hazır"),
    ERROR("Hata")
}

data class Attachment(
    val id: String,
    val uri: Uri,
    val name: String,
    val extension: String,
    val kind: AttachmentKind,
    val sizeBytes: Long,
    val status: AttachmentStatus,
    val error: String? = null
)

object FileTypes {
    val phar = setOf("phar")
    val zip = setOf("zip")
    val source = setOf(
        "php", "java", "kt", "py", "js", "ts", "html", "css",
        "c", "cpp", "h", "hpp", "gradle", "sh"
    )
    val config = setOf("yml", "yaml", "json", "xml", "properties", "toml", "ini", "conf")
    val text = setOf("txt", "md", "log")
    val image = setOf("png", "jpg", "jpeg", "webp", "gif")

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun kindOf(extension: String): AttachmentKind = when (extension) {
        in phar -> AttachmentKind.PHAR
        in zip -> AttachmentKind.ZIP
        in source -> AttachmentKind.SOURCE
        in config -> AttachmentKind.CONFIG
        in text -> AttachmentKind.TEXT
        in image -> AttachmentKind.IMAGE
        else -> AttachmentKind.UNKNOWN
    }
}
