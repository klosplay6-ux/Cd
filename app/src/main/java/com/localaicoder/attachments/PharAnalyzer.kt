package com.localaicoder.attachments

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Reads PHAR archives without invoking PHP or executing archive contents.
 * Supports PHP's native stub/manifest/contents format and tar-based PHARs.
 */
class PharAnalyzer {
    companion object {
        private const val MAX_ENTRIES = 10_000
        private const val MAX_ARCHIVE_BYTES = 200L * 1024 * 1024
        private const val MAX_TEXT_PER_ENTRY = 20_000
        private const val MAX_PROMPT_TEXT = 120_000
        private const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
        private const val MAX_STUB_SCAN = 2L * 1024 * 1024
        private const val TAR_BLOCK = 512
        private const val FLAG_ZLIB = 0x00001000
        private const val FLAG_BZIP2 = 0x00002000
        private val TEXT_EXTENSIONS = setOf(
            "php", "yml", "yaml", "json", "xml", "ini", "conf", "properties",
            "md", "txt", "log", "phar"
        )
        private val HALT_MARKER = "__HALT_COMPILER();".toByteArray(Charsets.US_ASCII)

        fun analyze(input: InputStream): Analysis {
            val temp = File.createTempFile("phar-analysis-", ".bin")
            try {
                input.use { source ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count <= 0) break
                            total += count
                            if (total > MAX_ARCHIVE_BYTES) {
                                throw IOException("PHAR dosyası izin verilen 200 MB sınırını aşıyor.")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                return parse(temp)
            } finally {
                temp.delete()
            }
        }

        private fun parse(file: File): Analysis {
            RandomAccessFile(file, "r").use { raf ->
                parseNativePhar(raf)?.let { return it }
                parseTarPhar(raf)?.let { return it }
                throw IOException("PHAR arşivi tanınamadı; dosya bozuk veya desteklenmeyen bir PHAR biçiminde.")
            }
        }

        /** PHP's native PHAR: stub, 4-byte manifest length, manifest, file contents, signature. */
        private fun parseNativePhar(raf: RandomAccessFile): Analysis? {
            val marker = findBytes(raf, HALT_MARKER, MAX_STUB_SCAN) ?: return null
            val candidateEnd = minOf(raf.length() - 28, marker + HALT_MARKER.size + 4096)
            var manifestOffset = marker + HALT_MARKER.size
            while (manifestOffset <= candidateEnd) {
                val manifestLength = readUInt32(raf, manifestOffset)
                if (manifestLength in 24..MAX_MANIFEST_BYTES && manifestOffset + 4 + manifestLength <= raf.length()) {
                    try {
                        return parseManifest(raf, manifestOffset, manifestLength)
                    } catch (_: InvalidPharException) {
                        // There may be whitespace or ?> between the stub and the binary manifest.
                    }
                }
                manifestOffset++
            }
            return null
        }

        private fun parseManifest(raf: RandomAccessFile, manifestOffset: Long, manifestLength: Long): Analysis {
            val manifestEnd = manifestOffset + 4 + manifestLength
            val reader = ManifestReader(raf, manifestOffset + 4, manifestEnd)
            val fileCount = reader.u32().toInt()
            if (fileCount <= 0 || fileCount > MAX_ENTRIES) throw InvalidPharException()
            reader.skip(2) // API version
            reader.skip(4) // global flags
            reader.skipSizedBytes() // alias
            reader.skipSizedBytes() // archive metadata (PHP serialized; never executed)

            val parsed = ArrayList<NativeEntry>(fileCount)
            repeat(fileCount) {
                val path = reader.string(reader.u32().toInt())
                val uncompressedSize = reader.u32()
                reader.skip(4) // timestamp
                val compressedSize = reader.u32()
                reader.skip(4) // CRC32
                val flags = reader.u32().toInt()
                reader.skipSizedBytes() // per-file metadata
                if (path.isBlank() || path.startsWith("/") || path.split('/').any { it == ".." }) {
                    throw IOException("PHAR içinde güvensiz yol bulundu: $path")
                }
                if (compressedSize > MAX_ARCHIVE_BYTES || uncompressedSize > MAX_ARCHIVE_BYTES) {
                    throw IOException("PHAR girdisi izin verilen boyutu aşıyor: $path")
                }
                if (flags and FLAG_BZIP2 != 0) {
                    throw IOException("PHAR girdisi bzip2 ile sıkıştırılmış; bu Android sürümünde desteklenmiyor: $path")
                }
                parsed += NativeEntry(path, uncompressedSize, compressedSize, flags)
            }
            if (reader.position != manifestEnd) throw InvalidPharException()

            var contentOffset = manifestEnd
            val entries = parsed.map { entry ->
                if (contentOffset + entry.compressedSize > raf.length()) throw InvalidPharException()
                val result = Entry(entry.path, entry.uncompressedSize, entry.path.endsWith("/"))
                contentOffset += entry.compressedSize
                result
            }
            val textFiles = mutableListOf<TextFile>()
            contentOffset = manifestEnd
            parsed.forEach { entry ->
                val extension = entry.path.substringAfterLast('.', "").lowercase()
                if (extension in TEXT_EXTENSIONS && entry.uncompressedSize <= MAX_TEXT_PER_ENTRY) {
                    val compressed = readBytes(raf, contentOffset, entry.compressedSize.toInt())
                    val body = decodeContent(compressed, entry.flags, entry.uncompressedSize.toInt())
                    if (body != null && body.none { it.toInt() == 0 }) {
                        textFiles += TextFile(entry.path, body.toString(Charsets.UTF_8))
                    }
                }
                contentOffset += entry.compressedSize
            }
            return Analysis(entries, textFiles)
        }

        /** Fallback for tar-based PHARs. */
        private fun parseTarPhar(raf: RandomAccessFile): Analysis? {
            val start = findTarStart(raf) ?: return null
            val entries = mutableListOf<Entry>()
            val textFiles = mutableListOf<TextFile>()
            var offset = start
            var zeroBlocks = 0
            while (offset + TAR_BLOCK <= raf.length()) {
                val header = readBytes(raf, offset, TAR_BLOCK)
                if (header.all { it == 0.toByte() }) {
                    zeroBlocks++
                    if (zeroBlocks == 2) break
                    offset += TAR_BLOCK
                    continue
                }
                zeroBlocks = 0
                val name = tarString(header, 0, 100)
                val size = tarNumber(header, 124, 12)
                val type = header[156].toInt().toChar()
                if (name.isBlank() || size < 0 || size > MAX_ARCHIVE_BYTES) throw IOException("PHAR içindeki geçersiz tar girdisi: $name")
                if (name.startsWith("/") || name.split('/').any { it == ".." }) throw IOException("PHAR içinde güvensiz yol bulundu: $name")
                if (entries.size >= MAX_ENTRIES || offset + TAR_BLOCK + size > raf.length()) throw IOException("PHAR girdisi arşiv sınırını aşıyor: $name")
                val directory = type == '5' || name.endsWith("/")
                entries += Entry(name, size, directory)
                val extension = name.substringAfterLast('.', "").lowercase()
                if (!directory && extension in TEXT_EXTENSIONS && size <= MAX_TEXT_PER_ENTRY) {
                    val body = readBytes(raf, offset + TAR_BLOCK, size.toInt()).toString(Charsets.UTF_8)
                    if (body.none { it.toInt() == 0 }) textFiles += TextFile(name, body)
                }
                offset += TAR_BLOCK + ((size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK
            }
            return if (entries.isEmpty()) null else Analysis(entries, textFiles)
        }

        private fun decodeContent(bytes: ByteArray, flags: Int, expectedSize: Int): ByteArray? {
            if (flags and FLAG_ZLIB == 0) return bytes
            return try {
                InflaterInputStream(ByteArrayInputStream(bytes), Inflater(true)).use { input ->
                    val output = ByteArray(expectedSize)
                    var read = 0
                    while (read < output.size) {
                        val count = input.read(output, read, output.size - read)
                        if (count <= 0) break
                        read += count
                    }
                    output.copyOf(read)
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun findTarStart(raf: RandomAccessFile): Long? {
            var candidate = findBytes(raf, HALT_MARKER, MAX_STUB_SCAN)?.plus(HALT_MARKER.size) ?: return null
            val end = minOf(raf.length() - TAR_BLOCK, candidate + 4096)
            while (candidate <= end) {
                if (isTarHeader(raf, candidate)) return candidate
                candidate++
            }
            return null
        }

        private fun isTarHeader(raf: RandomAccessFile, offset: Long): Boolean {
            if (offset < 0 || offset + TAR_BLOCK > raf.length()) return false
            val header = readBytes(raf, offset, TAR_BLOCK)
            val stored = tarNumber(header, 148, 8)
            if (stored < 0) return false
            var sum = 0L
            for (i in header.indices) sum += if (i in 148..155) 0x20 else header[i].toInt() and 0xff
            return sum == stored && tarString(header, 0, 100).isNotBlank()
        }

        private fun findBytes(raf: RandomAccessFile, needle: ByteArray, limit: Long): Long? {
            val max = minOf(raf.length(), limit)
            val buffer = ByteArray(8192)
            var offset = 0L
            var carry = ByteArray(0)
            while (offset < max) {
                raf.seek(offset)
                val count = raf.read(buffer, 0, minOf(buffer.size.toLong(), max - offset).toInt())
                if (count <= 0) break
                val combined = carry + buffer.copyOf(count)
                val index = combined.indexOfSubsequence(needle)
                if (index >= 0) return offset - carry.size + index
                carry = combined.takeLast(needle.size - 1).toByteArray()
                offset += count
            }
            return null
        }

        private fun tarString(bytes: ByteArray, start: Int, length: Int): String {
            val end = (start until start + length).firstOrNull { bytes[it].toInt() == 0 } ?: start + length
            return bytes.copyOfRange(start, end).toString(Charsets.UTF_8).trim()
        }

        private fun tarNumber(bytes: ByteArray, start: Int, length: Int): Long {
            val value = tarString(bytes, start, length).trim().trimStart('0')
            return if (value.isEmpty()) 0 else value.toLongOrNull(8) ?: -1
        }

        private fun readUInt32(raf: RandomAccessFile, offset: Long): Long {
            val bytes = readBytes(raf, offset, 4)
            return (bytes[0].toLong() and 0xff) or
                ((bytes[1].toLong() and 0xff) shl 8) or
                ((bytes[2].toLong() and 0xff) shl 16) or
                ((bytes[3].toLong() and 0xff) shl 24)
        }

        private fun readBytes(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
            if (length < 0 || length > MAX_ARCHIVE_BYTES) throw IOException("PHAR girdisi okunamayacak kadar büyük.")
            raf.seek(offset)
            val bytes = ByteArray(length)
            raf.readFully(bytes)
            return bytes
        }

        private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
            if (needle.isEmpty() || size < needle.size) return -1
            for (i in 0..size - needle.size) if (needle.indices.all { this[i + it] == needle[it] }) return i
            return -1
        }
    }

    private class InvalidPharException : Exception()

    private class ManifestReader(private val raf: RandomAccessFile, start: Long, private val end: Long) {
        var position: Long = start
            private set

        fun u32(): Long {
            ensure(4)
            val value = (raf.read().toLong() and 0xff) or
                ((raf.read().toLong() and 0xff) shl 8) or
                ((raf.read().toLong() and 0xff) shl 16) or
                ((raf.read().toLong() and 0xff) shl 24)
            position += 4
            return value
        }

        fun skip(count: Int) {
            if (count < 0) throw InvalidPharException()
            ensure(count)
            position += count
        }

        fun skipSizedBytes() {
            val size = u32()
            if (size > MAX_ARCHIVE_BYTES) throw InvalidPharException()
            skip(size.toInt())
        }

        fun string(length: Int): String {
            if (length < 1 || length > MAX_ARCHIVE_BYTES) throw InvalidPharException()
            ensure(length)
            val bytes = ByteArray(length)
            raf.readFully(bytes)
            position += length
            return bytes.toString(Charsets.UTF_8)
        }

        private fun ensure(count: Int) {
            if (position + count > end) throw InvalidPharException()
            raf.seek(position)
        }
    }

    private data class NativeEntry(val path: String, val uncompressedSize: Long, val compressedSize: Long, val flags: Int)
    data class Entry(val path: String, val sizeBytes: Long, val directory: Boolean)
    data class TextFile(val path: String, val content: String)
    data class Analysis(val entries: List<Entry>, val textFiles: List<TextFile>) {
        fun asPrompt(): String = buildString {
            append("PHAR arşiv analizi (dosyalar çalıştırılmadı):\n")
            append("Dosya ağacı:\n")
            entries.forEach { entry ->
                append(if (entry.directory) "[D] " else "[F] ")
                    .append(entry.path)
                    .append(if (entry.directory) "" else " (" + entry.sizeBytes + " bytes)")
                    .append('\n')
            }
            var used = 0
            textFiles.forEach { file ->
                if (used >= 120_000) return@forEach
                val content = file.content.take(120_000 - used)
                append("\n--- ").append(file.path).append(" ---\n").append(content).append('\n')
                used += content.length
            }
            entries.firstOrNull { it.path.substringAfterLast('/') == "plugin.yml" }?.let {
                append("\nNot: plugin.yml PHAR içinde bulundu: ").append(it.path).append('\n')
            }
        }
    }
}
