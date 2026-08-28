package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.MediaRepository
import com.example.media.ProcessingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val isError: Boolean = false
)

data class UiState(
    val urlInput: String = "",
    val isProcessing: Boolean = false,
    val progressPercent: Int = 0,
    val progressStatus: String = "",
    val isUpdatingYtDlp: Boolean = false,
    val ytDlpStatusMessage: String? = null,
    val lastResult: ProcessingResult? = null,
    val logs: List<LogEntry> = emptyList(),
    val errorMessage: String? = null
)

class MainViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = MediaRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    init {
        addLog("SYSTEM", "Система инициализирована. Готов к очистке и загрузке медиа.")
    }

    fun onUrlChange(newUrl: String) {
        _uiState.update { it.copy(urlInput = newUrl, errorMessage = null) }
    }

    fun updateYtDlp() {
        if (_uiState.value.isUpdatingYtDlp) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isUpdatingYtDlp = true,
                    ytDlpStatusMessage = "Обновление yt-dlp..."
                )
            }
            addLog("YTDLP", "Запуск асинхронного обновления yt-dlp бинарников...")

            val result = repository.updateYoutubeDL()
            result.onSuccess { status ->
                addLog("SUCCESS", "yt-dlp успешно обновлен: $status")
                _uiState.update {
                    it.copy(
                        isUpdatingYtDlp = false,
                        ytDlpStatusMessage = "yt-dlp актуален ($status)"
                    )
                }
            }.onFailure { error ->
                addLog("ERROR", "Ошибка обновления yt-dlp: ${error.message}", isError = true)
                _uiState.update {
                    it.copy(
                        isUpdatingYtDlp = false,
                        ytDlpStatusMessage = "Ошибка обновления: ${error.message}"
                    )
                }
            }
        }
    }

    fun downloadAndCleanUrl() {
        val url = _uiState.value.urlInput.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Пожалуйста, введите URL для скачивания") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProcessing = true,
                    progressPercent = 0,
                    progressStatus = "Подготовка к загрузке...",
                    errorMessage = null,
                    lastResult = null
                )
            }
            addLog("APP", "Старт процесса: Загрузка и очистка URL: $url")

            val result = repository.downloadAndProcessUrl(
                url = url,
                onLog = { tag, msg -> addLog(tag, msg, isError = tag == "ERROR") },
                onProgress = { percent, status ->
                    _uiState.update {
                        it.copy(progressPercent = percent, progressStatus = status)
                    }
                }
            )

            result.onSuccess { processingResult ->
                addLog("SUCCESS", "Обработка завершена успешно!")
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        progressPercent = 100,
                        progressStatus = "Готово!",
                        lastResult = processingResult
                    )
                }
            }.onFailure { error ->
                addLog("ERROR", "Сбой обработки: ${error.message}", isError = true)
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        progressPercent = 0,
                        progressStatus = "",
                        errorMessage = error.message ?: "Неизвестная ошибка"
                    )
                }
            }
        }
    }

    fun processLocalFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProcessing = true,
                    progressPercent = 0,
                    progressStatus = "Чтение выбранного файла...",
                    errorMessage = null,
                    lastResult = null
                )
            }
            addLog("APP", "Выбран локальный файл: $uri")

            val result = repository.processUri(
                uri = uri,
                onLog = { tag, msg -> addLog(tag, msg, isError = tag == "ERROR") },
                onProgress = { percent, status ->
                    _uiState.update {
                        it.copy(progressPercent = percent, progressStatus = status)
                    }
                }
            )

            result.onSuccess { processingResult ->
                addLog("SUCCESS", "Локальный файл успешно очищен!")
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        progressPercent = 100,
                        progressStatus = "Готово!",
                        lastResult = processingResult
                    )
                }
            }.onFailure { error ->
                addLog("ERROR", "Ошибка обработки локального файла: ${error.message}", isError = true)
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        progressPercent = 0,
                        progressStatus = "",
                        errorMessage = error.message ?: "Неизвестная ошибка"
                    )
                }
            }
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
        addLog("SYSTEM", "Логи очищены")
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun openFile(context: Context, filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                addLog("ERROR", "Файл не найден по пути: $filePath", isError = true)
                return
            }

            val uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Открыть файл"))
        } catch (e: Exception) {
            addLog("ERROR", "Не удалось открыть файл: ${e.message}", isError = true)
        }
    }

    fun shareFile(context: Context, filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                addLog("ERROR", "Файл не найден по пути: $filePath", isError = true)
                return
            }

            val uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Поделиться файлом"))
        } catch (e: Exception) {
            addLog("ERROR", "Не удалось поделиться файлом: ${e.message}", isError = true)
        }
    }

    private fun addLog(tag: String, message: String, isError: Boolean = false) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            message = message,
            isError = isError
        )
        _uiState.update {
            val updated = it.logs + entry
            it.copy(logs = if (updated.size > 200) updated.takeLast(200) else updated)
        }
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "pdf" -> "application/pdf"
            "mp3" -> "audio/mpeg"
            else -> "*/*"
        }
    }
}
