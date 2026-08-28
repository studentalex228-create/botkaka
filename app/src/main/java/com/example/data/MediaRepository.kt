package com.example.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.media.FileCategory
import com.example.media.MediaProcessor
import com.example.media.ProcessingResult
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class MediaRepository(
    private val context: Context,
    private val mediaProcessor: MediaProcessor = MediaProcessor(context)
) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun updateYoutubeDL(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context)
            Result.success(status?.name ?: "Обновлено успешно")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun processUri(
        uri: Uri,
        onLog: (tag: String, message: String) -> Unit,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<ProcessingResult> = withContext(Dispatchers.IO) {
        try {
            onLog("INFO", "Получение файла из URI: $uri")
            onProgress(5, "Копирование файла во временный буфер...")

            val originalName = getFileNameFromUri(uri) ?: "file_${System.currentTimeMillis()}"
            val tempDir = File(context.cacheDir, "input_temp").apply { mkdirs() }
            val tempFile = File(tempDir, originalName)

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IllegalStateException("Не удалось прочитать файл по URI")

            onLog("INFO", "Файл скопирован: ${tempFile.name} (${tempFile.length()} байт)")

            val result = mediaProcessor.processFile(tempFile, onLog, onProgress)
            if (result.isSuccess) {
                val processingResult = result.getOrThrow()
                // Also copy to public MediaStore for high accessibility
                exportToPublicMediaStore(File(processingResult.finalSavedPath), processingResult.fileType, onLog)
            }
            result
        } catch (e: Exception) {
            onLog("ERROR", "Ошибка при обработке URI: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun downloadAndProcessUrl(
        url: String,
        onLog: (tag: String, message: String) -> Unit,
        onProgress: (percent: Int, status: String) -> Unit
    ): Result<ProcessingResult> = withContext(Dispatchers.IO) {
        try {
            onLog("NETWORK", "Начало загрузки по ссылке: $url")
            onProgress(5, "Инициализация загрузки...")

            val downloadDir = File(context.cacheDir, "download_temp").apply { mkdirs() }
            val downloadedFile = downloadWithYtDlpOrFallback(url, downloadDir, onLog, onProgress)

            onLog("NETWORK", "Загрузка завершена: ${downloadedFile.name} (${downloadedFile.length()} байт)")

            val result = mediaProcessor.processFile(downloadedFile, onLog, onProgress)
            if (result.isSuccess) {
                val processingResult = result.getOrThrow()
                exportToPublicMediaStore(File(processingResult.finalSavedPath), processingResult.fileType, onLog)
            }
            result
        } catch (e: Exception) {
            onLog("ERROR", "Ошибка загрузки: ${e.message}")
            Result.failure(e)
        }
    }

    private fun downloadWithYtDlpOrFallback(
        url: String,
        outputDir: File,
        onLog: (String, String) -> Unit,
        onProgress: (Int, String) -> Unit
    ): File {
        val uniquePrefix = UUID.randomUUID().toString().take(8)
        val template = "${outputDir.absolutePath}/${uniquePrefix}_%(title)s.%(ext)s"

        try {
            onLog("YTDLP", "Попытка загрузки через yt-dlp...")
            val request = YoutubeDLRequest(url)
            request.addOption("-o", template)
            request.addOption("--no-playlist")
            request.addOption("-f", "best[ext=mp4]/best")

            YoutubeDL.getInstance().execute(request) { progress, eta, line ->
                val p = progress.toInt().coerceIn(0, 100)
                onProgress(5 + (p * 0.2).toInt(), "Скачивание yt-dlp: $p% (ETA: ${eta}s)")
                if (line.isNotBlank()) onLog("YTDLP_LOG", line)
            }

            val matchingFiles = outputDir.listFiles { _, name -> name.startsWith(uniquePrefix) }
            if (!matchingFiles.isNullOrEmpty()) {
                return matchingFiles.first()
            }
        } catch (e: Exception) {
            onLog("WARN", "yt-dlp не смог загрузить поток (${e.message}), попытка прямой HTTP загрузки...")
        }

        // Direct HTTP fallback download
        return downloadDirectHttp(url, outputDir, onLog, onProgress)
    }

    private fun downloadDirectHttp(
        url: String,
        outputDir: File,
        onLog: (String, String) -> Unit,
        onProgress: (Int, String) -> Unit
    ): File {
        onLog("HTTP", "Прямое скачивание файла по URL...")
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ошибка: ${response.code} ${response.message}")
        }

        val body = response.body ?: throw IllegalStateException("Пустой ответ сервера")
        val contentLength = body.contentLength()

        var fileName = url.substringAfterLast("/").substringBefore("?").ifEmpty { "download_${System.currentTimeMillis()}" }
        if (!fileName.contains(".")) {
            val contentType = response.header("Content-Type") ?: "application/octet-stream"
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType) ?: "bin"
            fileName = "$fileName.$ext"
        }

        val destination = File(outputDir, fileName)
        body.byteStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(8192)
                var totalBytesRead = 0L
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0) {
                        val percent = ((totalBytesRead * 100) / contentLength).toInt()
                        onProgress(5 + (percent * 0.2).toInt(), "Скачивание: $percent%")
                    }
                }
            }
        }
        return destination
    }

    private fun exportToPublicMediaStore(file: File, category: FileCategory, onLog: (String, String) -> Unit) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(file))
                    when (category) {
                        FileCategory.PHOTO -> {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Sanitized")
                        }
                        FileCategory.VIDEO -> {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Sanitized")
                        }
                        else -> {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Sanitized")
                        }
                    }
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collectionUri = when (category) {
                    FileCategory.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    FileCategory.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }

                val itemUri = resolver.insert(collectionUri, contentValues)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)
                    onLog("SYSTEM", "Файл экспортирован в системную галерею/загрузки: $itemUri")
                }
            }
        } catch (e: Exception) {
            onLog("WARN", "Не удалось экспортировать в MediaStore: ${e.message}")
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }
}
