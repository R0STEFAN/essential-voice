package com.ishaan.essentialvoice.whisper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable model download. Supports single file models and multi-file Parakeet models.
 */
object ModelDownloader {

    sealed interface State {
        data object Idle : State
        data class Running(val tierId: String, val done: Long, val total: Long) : State {
            val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
        }
        data class Failed(val tierId: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var cancelRequested = false

    fun cancel() { cancelRequested = true }

    /** Returns true when the model file ends up complete and the right size. */
    suspend fun download(context: Context, tier: QualityTier): Boolean = withContext(Dispatchers.IO) {
        cancelRequested = false
        if (tier.isInstalled(context)) {
            _state.value = State.Idle
            return@withContext true
        }

        // Multi-file download (e.g. Parakeet TDT ONNX models)
        if (tier.subFiles.isNotEmpty()) {
            val tierDir = File(ModelCatalog.dir(context), tier.id).apply { if (!exists()) mkdirs() }
            val totalBytes = if (tier.bytes > 0) tier.bytes else tier.subFiles.sumOf { it.second }
            var totalDone = 0L

            for ((fileName, fileBytes) in tier.subFiles) {
                if (cancelRequested) { _state.value = State.Idle; return@withContext false }
                val target = File(tierDir, fileName)
                if (target.isFile && (fileBytes <= 0 || target.length() == fileBytes)) {
                    totalDone += target.length()
                    continue
                }

                val fileUrl = "${tier.url}$fileName"
                val part = File(tierDir, "$fileName.part")
                var fileDone = if (part.isFile) part.length() else 0L

                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 30_000
                        instanceFollowRedirects = true
                        if (fileDone > 0) setRequestProperty("Range", "bytes=$fileDone-")
                    }
                    val code = conn.responseCode
                    if (fileDone > 0 && code == HttpURLConnection.HTTP_OK) {
                        part.delete()
                        fileDone = 0L
                    } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                        _state.value = State.Failed(tier.id, "Server returned $code for $fileName")
                        return@withContext false
                    }

                    RandomAccessFile(part, "rw").use { out ->
                        out.seek(fileDone)
                        conn.inputStream.use { input ->
                            val buf = ByteArray(256 * 1024)
                            var lastPublish = 0L
                            while (true) {
                                if (cancelRequested) {
                                    _state.value = State.Idle
                                    return@withContext false
                                }
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                fileDone += n
                                if (fileDone - lastPublish > 400_000) {
                                    lastPublish = fileDone
                                    _state.value = State.Running(tier.id, totalDone + fileDone, totalBytes)
                                }
                            }
                        }
                    }

                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        _state.value = State.Failed(tier.id, "Could not move $fileName into place")
                        return@withContext false
                    }
                    totalDone += target.length()
                } catch (t: Throwable) {
                    _state.value = State.Failed(tier.id, t.message ?: t.javaClass.simpleName)
                    return@withContext false
                } finally {
                    conn?.disconnect()
                }
            }

            _state.value = State.Idle
            return@withContext true
        }

        // Single-file download (e.g. Whisper GGML models)
        val target = tier.file(context)
        val part = File(target.parentFile, tier.fileName + ".part")
        var done = if (part.isFile) part.length() else 0L
        var total = tier.bytes
        if (total > 0 && done > total) { part.delete(); done = 0L }

        _state.value = State.Running(tier.id, done, total)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(tier.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }
            val code = conn.responseCode

            if (done > 0 && code == HttpURLConnection.HTTP_OK) {
                part.delete()
                done = 0L
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                _state.value = State.Failed(tier.id, "Server returned $code")
                return@withContext false
            }

            val cl = conn.contentLengthLong
            if (cl > 0) {
                total = if (done > 0) done + cl else cl
            }

            RandomAccessFile(part, "rw").use { out ->
                out.seek(done)
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var lastPublish = 0L
                    while (true) {
                        if (cancelRequested) {
                            _state.value = State.Idle
                            return@withContext false
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastPublish > 400_000) {
                            lastPublish = done
                            _state.value = State.Running(tier.id, done, total)
                        }
                    }
                }
            }

            if (total > 0 && part.length() != total) {
                _state.value = State.Failed(
                    tier.id, "Incomplete: got ${part.length() / 1_000_000}MB of ${total / 1_000_000}MB"
                )
                return@withContext false
            } else if (part.length() < 1_000_000L) {
                _state.value = State.Failed(tier.id, "File is too small to be a valid model")
                return@withContext false
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                _state.value = State.Failed(tier.id, "Could not move file into place")
                return@withContext false
            }

            val name = target.name.lowercase()
            if (name.endsWith(".tar.bz2") || name.endsWith(".zip") || name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                val outDir = File(ModelCatalog.dir(context), tier.id)
                extractArchive(target, outDir)
            }

            _state.value = State.Idle
            true
        } catch (t: Throwable) {
            _state.value = State.Failed(tier.id, t.message ?: t.javaClass.simpleName)
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun delete(context: Context, tier: QualityTier) {
        tier.file(context).delete()
        File(ModelCatalog.dir(context), tier.fileName + ".part").delete()
        val tierDir = File(ModelCatalog.dir(context), tier.id)
        if (tierDir.isDirectory) tierDir.deleteRecursively()
    }

    fun extractArchive(archiveFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val name = archiveFile.name.lowercase()

        if (name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".tar")) {
            runCatching {
                var rawIn: java.io.InputStream = archiveFile.inputStream().buffered()
                if (name.endsWith(".tar.bz2") || name.endsWith(".tbz2")) {
                    rawIn = org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(rawIn)
                } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                    rawIn = java.util.zip.GZIPInputStream(rawIn)
                }

                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(rawIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        val fileName = entry.name.substringAfterLast('/')
                        if (!entry.isDirectory && fileName.isNotBlank()) {
                            val destFile = File(targetDir, fileName)
                            destFile.outputStream().buffered().use { out ->
                                tarIn.copyTo(out)
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }.onFailure { android.util.Log.w("EVDownloader", "failed to extract tar archive", it) }
        } else if (name.endsWith(".zip")) {
            runCatching {
                java.util.zip.ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val fileName = entry.name.substringAfterLast('/')
                        if (!entry.isDirectory && fileName.isNotBlank()) {
                            val destFile = File(targetDir, fileName)
                            destFile.outputStream().buffered().use { out ->
                                zis.copyTo(out)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }.onFailure { android.util.Log.w("EVDownloader", "failed to extract zip archive", it) }
        }
    }
}
