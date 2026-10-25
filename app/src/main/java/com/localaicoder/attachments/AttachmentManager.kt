package com.localaicoder.attachments

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.util.UUID

/**
 * Sistem dosya seçicisinden gelen URI'leri inceler. Dosyaları çalıştırmaz;
 * yalnızca ad, boyut, tür ve okunabilirliği doğrular.
 */
class AttachmentManager(private val context: Context) {

    companion object {
        const val MAX_TEXT_BYTES = 2L * 1024 * 1024        // metin/kod dosyası üst sınırı
        const val MAX_ARCHIVE_BYTES = 200L * 1024 * 1024   // zip/phar üst sınırı
        private const val SNIFF_BYTES = 4096
    }

    suspend fun inspect(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (name, size) = queryNameAndSize(resolver, uri)
        val ext = FileTypes.extensionOf(name)
        val kind = FileTypes.kindOf(ext)
        val base = Attachment(
            id = UUID.randomUUID().toString(),
            uri = uri,
            name = name,
            extension = ext,
            kind = kind,
            sizeBytes = size,
            status = AttachmentStatus.READY
        )

        // Kalıcı okuma izni (mümkünse); sağlanamazsa oturum boyunca erişim yeterlidir.
        try {
            resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        } catch (_: UnsupportedOperationException) {
        }

        val limitError = when (kind) {
            AttachmentKind.PHAR, AttachmentKind.ZIP ->
                if (size > MAX_ARCHIVE_BYTES) "Arşiv çok büyük (üst sınır ${MAX_ARCHIVE_BYTES / (1024 * 1024)} MB)." else null
            AttachmentKind.IMAGE ->
                "Görsel analizi bu sürümde desteklenmiyor."
            else ->
                if (size > MAX_TEXT_BYTES) "Dosya çok büyük (üst sınır ${MAX_TEXT_BYTES / (1024 * 1024)} MB)." else null
        }
        if (limitError != null) return@withContext base.copy(status = AttachmentStatus.ERROR, error = limitError)

        try {
            val head = resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(SNIFF_BYTES)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            } ?: return@withContext base.copy(status = AttachmentStatus.ERROR, error = "Dosya açılamadı.")

            if (head.isEmpty()) {
                return@withContext base.copy(status = AttachmentStatus.ERROR, error = "Dosya boş.")
            }
            when (kind) {
                AttachmentKind.ZIP -> if (!(head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()))
                    return@withContext base.copy(status = AttachmentStatus.ERROR, error = "Geçerli bir ZIP imzası bulunamadı (bozuk olabilir).")
                AttachmentKind.SOURCE, AttachmentKind.CONFIG, AttachmentKind.TEXT -> if (head.any { it == 0.toByte() })
                    return@withContext base.copy(status = AttachmentStatus.ERROR, error = "Dosya ikili görünüyor; metin olarak okunmadı.")
                else -> Unit
            }
            base
        } catch (e: Exception) {
            base.copy(status = AttachmentStatus.ERROR, error = "Okuma hatası: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Reads safe source/text content for the model prompt; attachments are never executed. */
    suspend fun readForPrompt(attachment: Attachment): String = withContext(Dispatchers.IO) {
        if (attachment.status != AttachmentStatus.READY) return@withContext ""
        val resolver = context.contentResolver
        when (attachment.kind) {
            AttachmentKind.SOURCE, AttachmentKind.CONFIG, AttachmentKind.TEXT ->
                resolver.openInputStream(attachment.uri)?.use { readUtf8Limited(it, MAX_PROMPT_FILE_CHARS) } ?: ""
            AttachmentKind.ZIP, AttachmentKind.PHAR ->
                resolver.openInputStream(attachment.uri)?.use { input ->
                    ZipInputStream(input).use { zip ->
                        buildString {
                            var entry = zip.nextEntry
                            var total = 0
                            while (entry != null && total < MAX_ARCHIVE_PROMPT_CHARS) {
                                val name = entry.name
                                val ext = FileTypes.extensionOf(name)
                                val safe = !entry.isDirectory && !name.contains("..") && !name.startsWith("/")
                                if (safe && ext in (FileTypes.source + FileTypes.config + FileTypes.text)) {
                                    val body = readUtf8Limited(zip, MAX_ARCHIVE_ENTRY_CHARS)
                                    append("\n--- ").append(name).append(" ---\n").append(body).append('\n')
                                    total += body.length
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                    }
                } ?: ""
            else -> ""
        }
    }

    private fun readUtf8Limited(input: InputStream, limit: Int): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (bytes.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - bytes.size()))
            if (count <= 0) break
            if (buffer.take(count).any { it == 0.toByte() }) return "[ikili dosya atlandı]"
            bytes.write(buffer, 0, count)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val MAX_PROMPT_FILE_CHARS = 40_000
        const val MAX_ARCHIVE_ENTRY_CHARS = 20_000
        const val MAX_ARCHIVE_PROMPT_CHARS = 120_000
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "dosya"
        var size = -1L
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        return name to size
    }
}
