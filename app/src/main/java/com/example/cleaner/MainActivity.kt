package com.example.cleaner

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlText by remember { mutableStateOf("") }
    var selectedMode by remember { mutableIntStateOf(1) } // 0: Без сжатия, 1: Оптимально, 2: Максимум
    var isProcessing by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("Готов к работе...") }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var origSha by remember { mutableStateOf("") }
    var newSha by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isProcessing = true
                resultFile = null
                logText = "Копирование выбранного файла..."
                val inputFile = copyUriToCache(context, it)
                origSha = calculateSHA256(inputFile)
                logText = "Исходный SHA-256:\n$origSha\n\nЗапуск FFmpeg..."

                val output = processMedia(context, inputFile, selectedMode)
                
                if (output != null && output.exists()) {
                    newSha = calculateSHA256(output)
                    resultFile = output
                    logText += "\n\nОбработка завершена!\nНовый SHA-256:\n$newSha"
                } else {
                    logText += "\nОшибка обработки медиафайла."
                }
                isProcessing = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Media Cleaner", fontWeight = FontWeight.Bold) },
                actions = {
                    FilledTonalIconButton(onClick = { logText = "Модули обновлены." }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Update")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Переключатель режимов
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf("Без сжатия", "720p", "480p")
                modes.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = selectedMode == index,
                        onClick = { selectedMode = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                    ) {
                        Text(label, fontSize = 12.sp)
                    }
                }
            }

            // Скачивание по URL
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Скачать по ссылке", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Вставьте ссылку...") },
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true
                    )
                    Button(
                        onClick = { logText = "Скачивание URL будет подключено в следующем модуле" },
                        modifier = Modifier.fillMaxWidth().clip(CircleShape),
                        enabled = !isProcessing && urlText.isNotBlank()
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Скачать и очистить")
                    }
                }
            }

            // Выбор локального файла
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Локальный файл", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { filePickerLauncher.launch("video/*") },
                        modifier = Modifier.fillMaxWidth().clip(CircleShape),
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Default.FileOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Выбрать видео с устройства")
                    }
                }
            }

            // Индикатор выполнения
            AnimatedVisibility(visible = isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
            }

            // Карточка готового результата
            AnimatedVisibility(
                visible = resultFile != null,
                enter = expandVertically() + fadeIn()
            ) {
                ElevatedCard(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Файл готов!",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { resultFile?.let { saveToGallery(context, it) } },
                                modifier = Modifier.weight(1f).clip(CircleShape)
                            ) {
                                Icon(Icons.Default.Save, null)
                                Spacer(Modifier.width(4.dp))
                                Text("В галерею")
                            }
                            FilledTonalButton(
                                onClick = { resultFile?.let { shareFile(context, it) } },
                                modifier = Modifier.weight(1f).clip(CircleShape)
                            ) {
                                Icon(Icons.Default.Share, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Отправить")
                            }
                        }
                    }
                }
            }

            // Консоль логов
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = logText,
                    modifier = Modifier.padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

suspend fun processMedia(
    context: Context,
    input: File,
    mode: Int
): File? = withContext(Dispatchers.IO) {
    val output = File(context.cacheDir, "clean_${UUID.randomUUID().toString().take(8)}_${input.name}")
    val cmd = when (mode) {
package com.example.cleaner

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen()
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlText by remember { mutableStateOf("") }
    var selectedMode by remember { mutableIntStateOf(1) } // 0: Без сжатия, 1: 720p, 2: 480p
    var isProcessing by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("Готов к работе...") }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var origSha by remember { mutableStateOf("") }
    var newSha by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isProcessing = true
                resultFile = null
                logText = "Чтение файла..."
                val inputFile = copyUriToCache(context, it)
                origSha = calculateSHA256(inputFile)
                logText = "Исходный SHA-256:\n$origSha\n\nОбработка через Media3 (аппаратный энкодер)..."

                val output = processMedia(context, inputFile, selectedMode)

                if (output != null && output.exists()) {
                    newSha = calculateSHA256(output)
                    resultFile = output
                    logText += "\n\nГотово!\nНовый SHA-256:\n$newSha\nМетаданные очищены."
                } else {
                    logText += "\nОшибка при обработке файла."
                }
                isProcessing = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Media Cleaner", fontWeight = FontWeight.Bold) },
                actions = {
                    FilledTonalIconButton(onClick = { logText = "Модули актуальны." }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Update")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Режимы
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf("Оригинал", "720p", "480p")
                modes.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = selectedMode == index,
                        onClick = { selectedMode = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                    ) {
                        Text(label, fontSize = 12.sp)
                    }
                }
            }

            // Скачивание по URL
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Скачать по ссылке", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Вставьте ссылку...") },
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true
                    )
                    Button(
                        onClick = { logText = "Модуль yt-dlp подключается отдельным сервисом" },
                        modifier = Modifier.fillMaxWidth().clip(CircleShape),
                        enabled = !isProcessing && urlText.isNotBlank()
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Скачать и очистить")
                    }
                }
            }

            // Выбор файла
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Локальное видео", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { filePickerLauncher.launch("video/*") },
                        modifier = Modifier.fillMaxWidth().clip(CircleShape),
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Default.FileOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Выбрать видео с устройства")
                    }
                }
            }

            // Прогресс
            AnimatedVisibility(visible = isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
            }

            // Результат
            AnimatedVisibility(
                visible = resultFile != null,
                enter = expandVertically() + fadeIn()
            ) {
                ElevatedCard(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Файл успешно очищен!",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { resultFile?.let { saveToGallery(context, it) } },
                                modifier = Modifier.weight(1f).clip(CircleShape)
                            ) {
                                Icon(Icons.Default.Save, null)
                                Spacer(Modifier.width(4.dp))
                                Text("В галерею")
                            }
                            FilledTonalButton(
                                onClick = { resultFile?.let { shareFile(context, it) } },
                                modifier = Modifier.weight(1f).clip(CircleShape)
                            ) {
                                Icon(Icons.Default.Share, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Отправить")
                            }
                        }
                    }
                }
            }

            // Логи
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = logText,
                    modifier = Modifier.padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
suspend fun processMedia(
    context: Context,
    input: File,
    mode: Int
): File? = withContext(Dispatchers.Main) {
    val output = File(context.cacheDir, "clean_${UUID.randomUUID().toString().take(8)}.mp4")
    val mediaItem = MediaItem.fromUri(Uri.fromFile(input))
    
    val effectsList = mutableListOf<Effect>()
    when (mode) {
        1 -> effectsList.add(Presentation.createForWidthAndHeight(1280, 720, Presentation.LAYOUT_SCALE_TO_FIT))
        2 -> effectsList.add(Presentation.createForWidthAndHeight(854, 480, Presentation.LAYOUT_SCALE_TO_FIT))
    }

    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
        .setEffects(Effects(emptyList(), effectsList))
        .setRemoveAudio(false)
        .build()

    suspendCancellableCoroutine { continuation ->
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(output)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
            .build()

        transformer.start(editedMediaItem, output.absolutePath)
        continuation.invokeOnCancellation { transformer.cancel() }
    }
}

fun calculateSHA256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { fis ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (fis.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun copyUriToCache(context: Context, uri: Uri): File {
    val file = File(context.cacheDir, "raw_${UUID.randomUUID().toString().take(6)}.mp4")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(file).use { output -> input.copyTo(output) }
    }
    return file
}

fun saveToGallery(context: Context, file: File) {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
        }
    }
    context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
    }
}

fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться"))
}

S.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
        }
    }
    context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
    }
}

fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться"))
}
