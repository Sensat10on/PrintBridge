package com.printbridge.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.printbridge.core.PrinterProfile

/**
 * Saves the current job to a location the user chooses.
 *
 * The destination is picked through the Storage Access Framework, so both the folder and the file
 * name come from the system dialog and the app writes nothing into a fixed directory. Three shapes
 * are offered: the rendered pages as PDF, the exact printer bytes, and the document text.
 */
@Composable
internal fun ExportPanel(
    document: PrintableDocument?,
    profile: PrinterProfile,
    printerBytes: ByteArray,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val formats = remember(document) { document?.let { JobExporter.formatsFor(it) } ?: emptyList() }
    var format by remember(document) { mutableStateOf(formats.firstOrNull() ?: ExportFormat.RAW) }
    var fileName by remember(document, format) {
        mutableStateOf(
            document?.let { JobExporter.suggestedName(it.sourceName, format) } ?: "printbridge.bin"
        )
    }

    var pendingName by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument()) { target ->
        val current = document
        if (target == null) {
            onStatus("Экспорт отменён")
            return@rememberLauncherForActivityResult
        }
        if (current == null) {
            onStatus("Экспорт: сначала загрузите файл")
            return@rememberLauncherForActivityResult
        }
        runCatching {
            JobExporter.write(
                context = context,
                target = target,
                document = current,
                format = format,
                printerBytes = printerBytes
            )
        }.onSuccess {
            onStatus("Экспорт: сохранено как ${pendingName ?: fileName}")
        }.onFailure { error ->
            onStatus(error.message ?: "Экспорт не удался")
        }
    }

    Text("ЭКСПОРТ ЗАДАНИЯ", style = MaterialTheme.typography.titleMedium)
    Text(
        "Сохранение в выбранную папку. Место и имя файла выбираются в системном диалоге.",
        style = MaterialTheme.typography.bodySmall
    )

    if (document == null) {
        Text("Сначала загрузите файл: экспортировать пока нечего.")
        return
    }
    if (printerBytes.isEmpty()) {
        Text("Для профиля ${profile.protocol} нет байтов задания — экспорт недоступен.")
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        formats.forEach { candidate ->
            Button(
                onClick = {
                    format = candidate
                    fileName = JobExporter.suggestedName(document.sourceName, candidate)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (format == candidate) "✓ ${candidate.label}" else candidate.label, maxLines = 1)
            }
        }
    }
    Text("Формат: ${format.label}", style = MaterialTheme.typography.bodySmall)

    OutlinedTextField(
        value = fileName,
        onValueChange = { fileName = it },
        label = { Text("Имя файла") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = {
            val name = fileName.trim().ifBlank { JobExporter.suggestedName(document.sourceName, format) }
            pendingName = name
            saver.launch(name)
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("ВЫБРАТЬ ПАПКУ И СОХРАНИТЬ") }
}
