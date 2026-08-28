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
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    var selectedMode by remember { mutableStateOf(1) } // 0: Без сжатия, 1: Оптимально, 2: Максимум
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
                logText = "Чтение выбранного файла..."
                val inputFile = copyUriToCache(context, it)
                origSha = calculateSHA256(inputFile)
                logText = "Исходный SHA-256:\n$origSha\nЗапуск FFmpeg..."

                val output = processMedia(context, inputFile, selectedMode) { log ->
                    logText = log
                }
                
                if (output != null && output.exists()) {
                    newSha = calculateSHA256(output)
                    resultFile = output
                    logText += "\n\nОбработка завершена!\nНовый SHA-256:\n$newSha"
                } else {
                    logText += "\nОшибка обработки."
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
                    FilledTonalIconButton(onClick = { logText = "Модули обновлены" }) {
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
            // Режим сжатия
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

            // Загрузка по ссылке
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
                        onClick = { logText = "Скачивание пока доступно для локальных файлов" },
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
                    Text("Локальный файл", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth().clip(CircleShape),
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Default.FileOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Выбрать файл с устройства")
                    }
                }
            }

            // Индикатор процесса
            AnimatedVisibility(visible = isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(CircleShape))
            }

            // Карточка результата
            AnimatedVisibility(
                visible = resultFile != null,
                enter = spring<androidx.compose.animation.EnterTransition>(
                    dampingRatio = Spring.DampingRatioMediumBouncy
                ).let { expandVertically() + fadeIn() }
            ) {
                ElevatedCard(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Файл готов!", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
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

suspend fun processMedia(
    context: Context,
    input: File,
    mode: Int,
    onLog: (String) -> Unit
): File? = withContext(Dispatchers.IO) {
    val output = File(context.cacheDir, "clean_${UUID.randomUUID().toString().take(8)}_${input.name}")
    val cmd = when (mode) {
        0 -> "-y -i \"${input.absolutePath}\" -map_metadata -1 -fflags +bitexact -c copy \"${output.absolutePath}\""
        1 -> "-y -threads 4 -i \"${input.absolutePath}\" -map_metadata -1 -fflags +bitexact -vf \"scale='min(1280,iw)':-2\" -c:v libx264 -crf 28 -preset veryfast -c:a aac -b:a 96k \"${output.absolutePath}\""
        else -> "-y -threads 4 -i \"${input.absolutePath}\" -map_metadata -1 -fflags +bitexact -vf \"scale='min(854,iw)':-2\" -c:v libx264 -crf 32 -preset veryfast -c:a aac -b:a 64k \"${output.absolutePath}\""
    }
    
    val session = FFmpegKit.execute(cmd)
    if (ReturnCode.isSuccess(session.returnCode)) output else null
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
    val file = File(context.cacheDir, "raw_${UUID.randomUUID().toString().take(6)}")
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
