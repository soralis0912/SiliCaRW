package jp.muo.silicarw.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ReadTabContent(
    readOptionLastError: Boolean,
    onReadOptionLastErrorChange: (Boolean) -> Unit,
    readOptionSystemCodes: Boolean,
    onReadOptionSystemCodesChange: (Boolean) -> Unit,
    readOptionServiceCodes: Boolean,
    onReadOptionServiceCodesChange: (Boolean) -> Unit,
    readOptionCustomBlock: Boolean,
    onReadOptionCustomBlockChange: (Boolean) -> Unit,
    readCustomBlockNumber: String,
    onReadCustomBlockNumberChange: (String) -> Unit,
    controlsEnabled: Boolean,
    isReadMode: Boolean,
    isReadButtonEnabled: Boolean,
    isCancelButtonEnabled: Boolean,
    onReadClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Read Options",
            style = MaterialTheme.typography.titleMedium
        )

        ReadOptionRow(
            label = "Last Error Command",
            checked = readOptionLastError,
            onCheckedChange = onReadOptionLastErrorChange,
            enabled = controlsEnabled
        )
        ReadOptionRow(
            label = "System Codes (0x85)",
            checked = readOptionSystemCodes,
            onCheckedChange = onReadOptionSystemCodesChange,
            enabled = controlsEnabled
        )
        ReadOptionRow(
            label = "Service Codes (0x84)",
            checked = readOptionServiceCodes,
            onCheckedChange = onReadOptionServiceCodesChange,
            enabled = controlsEnabled
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = readOptionCustomBlock,
                onCheckedChange = onReadOptionCustomBlockChange,
                enabled = controlsEnabled
            )
            Text(
                text = "Custom Block 読み取り",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = readCustomBlockNumber,
                onValueChange = onReadCustomBlockNumberChange,
                label = { Text("ブロック番号 (0-255)") },
                modifier = Modifier.width(160.dp),
                singleLine = true,
                enabled = controlsEnabled && readOptionCustomBlock,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("10進数で入力") }
            )
        }

        Button(
            onClick = onReadClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = isReadButtonEnabled
        ) {
            Text(
                text = if (isReadMode) "Read待機中..." else "Read",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Button(
            onClick = onCancelClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = isCancelButtonEnabled
        ) {
            Text(
                text = "Cancel",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
fun ReadOptionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
