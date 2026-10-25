package com.localaicoder.inference

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Copies and validates user-selected GGUF files without requiring broad storage permission. */
class ModelManager(private val context: Context) {
    private val modelDir: File = File(context.filesDir, "models").apply { mkdirs() }

    suspend fun importModel(uri: Uri): ImportedModel = withContext(Dispatchers.IO) {
        val displayName = queryName(uri)
        if (!displayName.lowercase().endsWith(".gguf")) {
            throw IOException("Yalnızca .gguf model dosyaları seçilebilir.")
        }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = uniqueDestination(File(modelDir, safeName))
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER) }
        } ?: throw IOException("Model dosyası okunamadı.")
        validateGguf(destination)
        ImportedModel(destination.name, destination.absolutePath, destination.length())
    }

    fun list(): List<ImportedModel> = modelDir.listFiles { file ->
        file.isFile && file.extension.equals("gguf", ignoreCase = true)
    }?.sortedBy { it.name.lowercase() }?.map { ImportedModel(it.name, it.absolutePath, it.length()) }
        ?: emptyList()

    fun delete(model: ImportedModel) { File(model.path).delete() }

    private fun validateGguf(file: File) {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || magic[0] != 'G'.code.toByte() ||
                magic[1] != 'G'.code.toByte() || magic[2] != 'U'.code.toByte() ||
                magic[3] != 'F'.code.toByte()) {
                file.delete()
                throw IOException("Dosya geçerli bir GGUF modeli değil veya bozuk.")
            }
        }
    }

    private fun queryName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
    }

    private fun uniqueDestination(original: File): File {
        if (!original.exists()) return original
        val base = original.nameWithoutExtension
        var i = 2
        var candidate: File
        do { candidate = File(modelDir, "${base}_$i.gguf"); i++ } while (candidate.exists())
        return candidate
    }

    data class ImportedModel(val name: String, val path: String, val sizeBytes: Long)

    private companion object { const val DEFAULT_BUFFER = 1024 * 1024 }
}
