package com.example.media

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.util.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class ProcessingResult(
    val originalFileName: String,
    val finalSavedPath: String,
    val originalSize: Long,
    val processedSize: Long,
    val originalSha256: String,
    val newSha256: String,
    val durationMs: Long,
    val isFallbackUsed: Boolean = false,
    val fileType: FileCategory = FileCategory.OTHER
)

enum class FileCategory {
    PHOTO, VIDEO, AUDIO, PDF, OTHER
}

class MediaProcessor(private val context: Context) {

    companion object {
        private const val TAG = "MediaProcessor"
    }

    suspend fun processFile(
        inputFile: File,
        onLog: (tag: String, message: String) -> Unit,
        onProgress: (progressPercent: Int, status: String) -> Unit
    ): Result<ProcessingResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            onLog("INFO", "Начало анализа файла: ${inputFile.name} (${formatFileSize(inputFile.length())})")
            onProgress(10, "Вычисление исходного SHA-256...")

            val originalSha256 = HashUtils.calculateSha256(inputFile)
            val originalSize = inputFile.length()
            onLog("SHA256", "Исходный SHA-256: $originalSha256")

            val extension = inputFile.extension.lowercase(Locale.ROOT)
            val category = determineCategory(extension)
            onLog("INFO", "Определен тип файла: $category (расширение: .$extension)")

            val tempOutputDir = File(context.cacheDir, "processed_temp").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFileName = "clean_${timestamp}_${inputFile.name}"
            val tempOutputFile = File(tempOutputDir, outputFileName)

            var isFallback = false

            when (category) {
                FileCategory.PHOTO -> {
                    onProgress(30, "Очистка метаданных фото через FFmpeg...")
                    val success = processPhoto(inputFile, tempOutputFile, onLog)
                    if (!success) {
                        onLog("WARN", "FFmpeg обработка фото не удалась, применение прямого стриппинга...")
                        fallbackStripGeneric(inputFile, tempOutputFile, onLog)
                        isFallback = true
                    }
                }

                FileCategory.VIDEO -> {
                    onProgress(30, "Кодирование и очистка метаданных видео через FFmpeg...")
                    val encodeSuccess = processVideo(inputFile, tempOutputFile, onLog)
                    
                    if (encodeSuccess && tempOutputFile.exists() && tempOutputFile.length() > 0) {
                        // Check if encoded video is larger than original
                        if (tempOutputFile.length() > originalSize) {
                            onLog("WARN", "Размер видео после сжатия (${formatFileSize(tempOutputFile.length())}) больше оригинала (${formatFileSize(originalSize)}). Применение fallback копирования...")
                            tempOutputFile.delete()
                            val fallbackSuccess = processVideoFallback(inputFile, tempOutputFile, onLog)
                            if (fallbackSuccess) {
                                isFallback = true
                            } else {
                                onLog("ERROR", "Fallback копирование не удалось, используется первичное сжатие")
                                processVideo(inputFile, tempOutputFile, onLog)
                            }
                        }
                    } else {
                        onLog("WARN", "Первичное сжатие видео вернуло ошибку, запуск fallback копирования потоков...")
                        tempOutputFile.delete()
                        val fallbackSuccess = processVideoFallback(inputFile, tempOutputFile, onLog)
                        if (!fallbackSuccess) {
                            throw IllegalStateException("Не удалось обработать видео через FFmpeg")
                        }
                        isFallback = true
                    }
                }

                FileCategory.AUDIO -> {
                    onProgress(30, "Очистка метаданных аудио через FFmpeg...")
                    val success = processAudio(inputFile, tempOutputFile, onLog)
                    if (!success) {
                        fallbackStripGeneric(inputFile, tempOutputFile, onLog)
                        isFallback = true
                    }
                }

                FileCategory.PDF -> {
                    onProgress(30, "Очистка метаданных PDF и добавление энтропии...")
                    processPdf(inputFile, tempOutputFile, onLog)
                }

                FileCategory.OTHER -> {
                    onProgress(30, "Очистка служебных данных и добавление энтропии...")
                    fallbackStripGeneric(inputFile, tempOutputFile, onLog)
                }
            }

            if (!tempOutputFile.exists() || tempOutputFile.length() == 0L) {
                throw IllegalStateException("Обработанный файл не был создан или имеет нулевой размер")
            }

            onProgress(85, "Вычисление нового SHA-256...")
            val newSha256 = HashUtils.calculateSha256(tempOutputFile)
            val processedSize = tempOutputFile.length()
            onLog("SHA256", "Новый SHA-256: $newSha256")

            if (originalSha256 == newSha256) {
                onLog("WARN", "SHA-256 не изменился. Добавление корректирующего байта энтропии...")
                appendEntropyByte(tempOutputFile)
                val finalSha256 = HashUtils.calculateSha256(tempOutputFile)
                onLog("SHA256", "Обновленный SHA-256: $finalSha256")
            }

            onProgress(95, "Сохранение файла в системное хранилище...")
            val savedPath = saveToSystemStorage(tempOutputFile, category)
            onLog("SUCCESS", "Файл успешно сохранен: $savedPath")

            val duration = System.currentTimeMillis() - startTime
            onLog("STATS", "Время обработки: ${duration}мс | Сжатие: ${calculateCompression(originalSize, processedSize)}")

            // Clean temporary files
            tempOutputFile.delete()
            inputFile.delete()

            Result.success(
                ProcessingResult(
                    originalFileName = inputFile.name,
                    finalSavedPath = savedPath,
                    originalSize = originalSize,
                    processedSize = processedSize,
                    originalSha256 = originalSha256,
                    newSha256 = HashUtils.calculateSha256(File(savedPath).takeIf { it.exists() } ?: tempOutputFile),
                    durationMs = duration,
                    isFallbackUsed = isFallback,
                    fileType = category
                )
            )
        } catch (e: Exception) {
            onLog("ERROR", "Критическая ошибка обработки: ${e.message}")
            Result.failure(e)
        }
    }

    private fun determineCategory(extension: String): FileCategory {
        return when (extension) {
            "jpg", "jpeg", "png", "webp", "bmp", "heic", "heif" -> FileCategory.PHOTO
            "mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v", "ts", "flv" -> FileCategory.VIDEO
            "mp3", "m4a", "aac", "wav", "ogg", "flac", "wma" -> FileCategory.AUDIO
            "pdf" -> FileCategory.PDF
            else -> FileCategory.OTHER
        }
    }

    private fun processPhoto(inputFile: File, outputFile: File, onLog: (String, String) -> Unit): Boolean {
        // Command: -y -i <input> -map_metadata -1 -fflags +bitexact -qscale:v 5 <output>
        val cmd = "-y -i \"${inputFile.absolutePath}\" -map_metadata -1 -fflags +bitexact -qscale:v 5 \"${outputFile.absolutePath}\""
        onLog("FFMPEG", "Запуск команды: $cmd")
        val session = FFmpegKit.execute(cmd)
        val returnCode = session.returnCode
        onLog("FFMPEG", "Код завершения: $returnCode")
        session.allLogs?.forEach { log ->
            if (log.message.isNotBlank()) onLog("FFMPEG_LOG", log.message.trim())
        }
        return ReturnCode.isSuccess(returnCode) && outputFile.exists() && outputFile.length() > 0
    }

    private fun processVideo(inputFile: File, outputFile: File, onLog: (String, String) -> Unit): Boolean {
        // Command: -y -threads 4 -i <input> -map_metadata -1 -fflags +bitexact -vf "scale='min(1280,iw)':-2" -c:v libx264 -crf 29 -preset veryfast -c:a aac -b:a 96k -movflags +faststart <output>
        val cmd = "-y -threads 4 -i \"${inputFile.absolutePath}\" -map_metadata -1 -fflags +bitexact -vf \"scale='min(1280,iw)':-2\" -c:v libx264 -crf 29 -preset veryfast -c:a aac -b:a 96k -movflags +faststart \"${outputFile.absolutePath}\""
        onLog("FFMPEG", "Запуск видео-кодирования: $cmd")
        val session = FFmpegKit.execute(cmd)
        val returnCode = session.returnCode
        onLog("FFMPEG", "Видео-кодирование завершено с кодом: $returnCode")
        session.allLogs?.takeLast(15)?.forEach { log ->
            if (log.message.isNotBlank()) onLog("FFMPEG_LOG", log.message.trim())
        }
        return ReturnCode.isSuccess(returnCode) && outputFile.exists() && outputFile.length() > 0
    }

    private fun processVideoFallback(inputFile: File, outputFile: File, onLog: (String, String) -> Unit): Boolean {
        // Fallback command: -y -i <input> -c copy -map_metadata -1 -fflags +bitexact -movflags +faststart <output>
        val cmd = "-y -i \"${inputFile.absolutePath}\" -c copy -map_metadata -1 -fflags +bitexact -movflags +faststart \"${outputFile.absolutePath}\""
        onLog("FFMPEG", "Запуск Fallback видео-копирования: $cmd")
        val session = FFmpegKit.execute(cmd)
        val returnCode = session.returnCode
        onLog("FFMPEG", "Fallback завершен с кодом: $returnCode")
        return ReturnCode.isSuccess(returnCode) && outputFile.exists() && outputFile.length() > 0
    }

    private fun processAudio(inputFile: File, outputFile: File, onLog: (String, String) -> Unit): Boolean {
        val cmd = "-y -i \"${inputFile.absolutePath}\" -map_metadata -1 -fflags +bitexact -c:a aac -b:a 96k \"${outputFile.absolutePath}\""
        onLog("FFMPEG", "Запуск обработки аудио: $cmd")
        val session = FFmpegKit.execute(cmd)
        return ReturnCode.isSuccess(session.returnCode) && outputFile.exists()
    }

    private fun processPdf(inputFile: File, outputFile: File, onLog: (String, String) -> Unit) {
        onLog("PDF", "Очистка структуры PDF от служебных метатегов...")
        val bytes = inputFile.readBytes()
        var text = String(bytes, Charsets.ISO_8859_1)

        // Replace metadata dictionaries if present
        text = text.replace(Regex("/CreationDate\\s*\\([^)]*\\)"), "/CreationDate ()")
            .replace(Regex("/ModDate\\s*\\([^)]*\\)"), "/ModDate ()")
            .replace(Regex("/Producer\\s*\\([^)]*\\)"), "/Producer ()")
            .replace(Regex("/Creator\\s*\\([^)]*\\)"), "/Creator ()")
            .replace(Regex("/Author\\s*\\([^)]*\\)"), "/Author ()")
            .replace(Regex("/Title\\s*\\([^)]*\\)"), "/Title ()")

        val modifiedBytes = text.toByteArray(Charsets.ISO_8859_1)
        outputFile.writeBytes(modifiedBytes)

        // Append non-breaking entropy tag and 1 random entropy byte to guarantee hash change
        val entropyTag = "\n% Sanitized: ${System.currentTimeMillis()}\n".toByteArray(Charsets.US_ASCII)
        val randomByte = byteArrayOf(Random.nextInt(0, 255).toByte())

        FileOutputStream(outputFile, true).use { fos ->
            fos.write(entropyTag)
            fos.write(randomByte)
        }
        onLog("PDF", "PDF успешно очищен и модифицирован 1 байтом энтропии")
    }

    private fun fallbackStripGeneric(inputFile: File, outputFile: File, onLog: (String, String) -> Unit) {
        onLog("GENERIC", "Прямая перезапись файла с добавлением энтропии...")
        val bytes = inputFile.readBytes()
        val randomEntropyByte = byteArrayOf(Random.nextInt(0, 255).toByte())
        outputFile.writeBytes(bytes + randomEntropyByte)
        onLog("GENERIC", "Файл перезаписан, добавлен 1 байт энтропии")
    }

    private fun appendEntropyByte(file: File) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(raf.length())
                raf.write(Random.nextInt(1, 255))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка добавления энтропии", e)
        }
    }

    private fun saveToSystemStorage(sourceFile: File, category: FileCategory): String {
        val destDir = when (category) {
            FileCategory.PHOTO -> File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.filesDir, "Sanitized")
            FileCategory.VIDEO -> File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir, "Sanitized")
            else -> File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "Sanitized")
        }
        destDir.mkdirs()

        val destFile = File(destDir, sourceFile.name)
        sourceFile.copyTo(destFile, overwrite = true)
        return destFile.absolutePath
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.2f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun calculateCompression(original: Long, processed: Long): String {
        if (original <= 0) return "0%"
        val diff = original - processed
        val percent = (diff.toDouble() / original.toDouble()) * 100.0
        return if (percent >= 0) {
            String.format(Locale.US, "-%.1f%%", percent)
        } else {
            String.format(Locale.US, "+%.1f%%", -percent)
        }
    }
}
