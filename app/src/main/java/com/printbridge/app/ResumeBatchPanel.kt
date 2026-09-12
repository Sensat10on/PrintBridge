package com.printbridge.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Retry control for a batch that stopped partway.
 *
 * A multi-page document that fails on sheet five leaves the user with a half-printed document and
 * no way to continue; [BatchPrintResult] now reports how far the batch got, so this offers to send
 * the rest instead of asking for the whole job again.
 */
@Composable
internal fun ResumeBatchPanel(
    result: BatchPrintResult?,
    onResume: () -> Unit
) {
    val hint = result?.resumeHint() ?: return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
            Text("ПРОДОЛЖИТЬ С ${(result.sentBeforeFailure + 1)}-го ЛИСТА")
        }
    }
}
