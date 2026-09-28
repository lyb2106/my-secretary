package com.lyb.mysecretary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyb.mysecretary.data.HistoryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val format = DateTimeFormatter.ofPattern("M/d (E) HH:mm")

private fun HistoryEntry.timeLabel(): String = Instant.ofEpochMilli(id).atZone(ZoneId.systemDefault()).format(format)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<HistoryEntry?>(null) }

    if (entries.isEmpty()) {
        Text("최근 7일간 변환 기록이 없습니다.", modifier.padding(16.dp))
    } else {
        Column(modifier.fillMaxSize()) {
            Text(
                "길게 누르면 삭제할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { e ->
                    Column(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { onOpen(e.id) }, onLongClick = { pendingDelete = e })
                            .padding(16.dp),
                    ) {
                        Text(e.timeLabel(), style = MaterialTheme.typography.titleSmall)
                        val preview = e.edited.lines().drop(1).joinToString(" / ") { it.removePrefix("- [ ] ") }
                        Text(
                            preview.ifBlank { "(할 일 없음)" },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("기록 삭제") },
            text = { Text("${e.timeLabel()} 변환 기록을 삭제할까요? 녹음 파일은 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = { onDelete(e.id); pendingDelete = null }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }
}
