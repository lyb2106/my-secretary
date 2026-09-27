package com.lyb.mysecretary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyb.mysecretary.data.HistoryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val format = DateTimeFormatter.ofPattern("M/d (E) HH:mm")

@Composable
fun HistoryScreen(entries: List<HistoryEntry>, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Text("최근 7일간 변환 기록이 없습니다.", modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { e ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(e.id) }.padding(16.dp)) {
                Text(Instant.ofEpochMilli(e.id).atZone(ZoneId.systemDefault()).format(format), style = MaterialTheme.typography.titleSmall)
                Text(
                    e.edited.lines().drop(1).joinToString(" / ") { it.removePrefix("- [ ] ") },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
        }
    }
}
