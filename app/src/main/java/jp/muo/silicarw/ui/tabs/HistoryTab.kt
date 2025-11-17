package jp.muo.silicarw.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import jp.muo.silicarw.HistoryEntry

@Composable
fun HistoryTabContent(
    readHistory: List<HistoryEntry>,
    onCopyEntry: (String) -> Unit,
    onDeleteEntry: (HistoryEntry) -> Unit,
    onClearAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "読み取り履歴",
                style = MaterialTheme.typography.titleMedium
            )
            if (readHistory.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text("全て削除")
                }
            }
        }

        if (readHistory.isEmpty()) {
            Text(
                text = "履歴はまだありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            readHistory.forEach { entry ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = entry.timestamp,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = entry.content,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { onCopyEntry(entry.content) }) {
                                Text("Copy")
                            }
                            TextButton(onClick = { onDeleteEntry(entry) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}
