package jp.muo.silicarw.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.muo.silicarw.WriteCommandType
import jp.muo.silicarw.util.tryAcceptHexInput

@Composable
fun WriteTabContent(
    controlsEnabled: Boolean,
    selectedCommand: WriteCommandType,
    onCommandChange: (WriteCommandType) -> Unit,
    idmInput: String,
    onInputIdmChange: (String) -> Unit,
    pmmInput: String,
    onPmmChange: (String) -> Unit,
    systemCodesInput: String,
    onSystemCodesChange: (String) -> Unit,
    serviceCodesInput: String,
    onServiceCodesChange: (String) -> Unit,
    rawBlockNumberInput: String,
    onRawBlockNumberChange: (String) -> Unit,
    rawDataInput: String,
    onRawDataChange: (String) -> Unit
) {
    Column {
        Text(
            text = "書き込み対象",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        WriteCommandType.values().forEach { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(enabled = controlsEnabled) { onCommandChange(type) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedCommand == type,
                    onClick = { if (controlsEnabled) onCommandChange(type) },
                    enabled = controlsEnabled
                )
                Text(
                    text = type.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.padding(top = 16.dp))

        when (selectedCommand) {
            WriteCommandType.IDM -> {
                OutlinedTextField(
                    value = idmInput,
                    onValueChange = { newValue ->
                        tryAcceptHexInput(newValue, 16)?.let(onInputIdmChange)
                    },
                    label = { Text("新しいIDm (16桁16進数)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = controlsEnabled,
                    supportingText = { Text("例: DEADBEEF01010101 (8バイト)") }
                )

                Spacer(modifier = Modifier.padding(top = 12.dp))

                OutlinedTextField(
                    value = pmmInput,
                    onValueChange = { newValue ->
                        tryAcceptHexInput(newValue, 16)?.let(onPmmChange)
                    },
                    label = { Text("PMm (任意 16桁)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = controlsEnabled,
                    supportingText = { Text("省略時はデフォルト値を使用します") }
                )
            }

            WriteCommandType.SYSTEM_CODES -> {
                OutlinedTextField(
                    value = systemCodesInput,
                    onValueChange = { newValue ->
                        tryAcceptHexInput(newValue, 16)?.let(onSystemCodesChange)
                    },
                    label = { Text("System Codes") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = controlsEnabled,
                    supportingText = { Text("最大4件・各4桁 (例: 12FC8000)") }
                )
            }

            WriteCommandType.SERVICE_CODES -> {
                OutlinedTextField(
                    value = serviceCodesInput,
                    onValueChange = { newValue ->
                        tryAcceptHexInput(newValue, 16)?.let(onServiceCodesChange)
                    },
                    label = { Text("Service Codes") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = controlsEnabled,
                    supportingText = { Text("最大4件・各4桁 (例: 100B200B)") }
                )
            }

            WriteCommandType.RAW_BLOCK -> {
                OutlinedTextField(
                    value = rawBlockNumberInput,
                    onValueChange = { newValue ->
                        if (!controlsEnabled) return@OutlinedTextField
                        if (newValue.length <= 2 && newValue.all { it.isDigit() }) {
                            onRawBlockNumberChange(newValue)
                        } else if (newValue.isEmpty()) {
                            onRawBlockNumberChange("")
                        }
                    },
                    label = { Text("ブロック番号 (0〜11)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = controlsEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.padding(top = 12.dp))

                OutlinedTextField(
                    value = rawDataInput,
                    onValueChange = { newValue ->
                        if (!controlsEnabled) return@OutlinedTextField
                        tryAcceptHexInput(newValue, 32)?.let(onRawDataChange)
                    },
                    label = { Text("16バイトのデータ (32桁16進数)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = controlsEnabled,
                    supportingText = { Text("例: 00112233445566778899AABBCCDDEEFF") }
                )
            }
        }
    }
}
