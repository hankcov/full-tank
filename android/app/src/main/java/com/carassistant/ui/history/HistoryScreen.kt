package com.carassistant.ui.history

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carassistant.data.entity.FuelRecord
import com.carassistant.data.repo.ImportSummary
import com.carassistant.domain.SegmentAnomaly
import com.carassistant.ui.AppUiState
import com.carassistant.ui.Fmt

/**
 * 历史列表。按日期倒序，异常行原地标注而不是隐藏 ——
 * 用户得看见「哪一条可能录错了」，藏起来他就永远不知道数据有问题。
 */
@Composable
fun HistoryScreen(
    state: AppUiState,
    contentPadding: PaddingValues,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    message: String?,
    onDismissMessage: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<FuelRecord?>(null) }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let(onExport) }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImport) }

    // 排序结果要缓存：不 remember 的话，每次重组（滚动、弹窗、切页）都要重跑一遍
    val sorted = remember(state.records) {
        state.records.sortedWith(
            compareByDescending<FuelRecord> { it.date }.thenByDescending { it.odometer }
        )
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
    ) {
        item {
            // 导入导出是低频操作，收进右上角「更多」里，别让它跟记录列表抢版面
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.current?.name ?: "历史",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                MoreMenu(
                    onExport = {
                        // 文件名带车名和日期，导多份时不会互相覆盖
                        val name = state.current?.name?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "满箱"
                        createDoc.launch("${name}_${java.time.LocalDate.now()}.csv")
                    },
                    onImport = {
                        openDoc.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*"))
                    },
                )
            }
        }

        if (sorted.isEmpty()) {
            item {
                Text(
                    "还没有记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }

        items(sorted, key = { it.id }) { r ->
            val seg = state.segments[r.id]
            HistoryRow(
                record = r,
                l100 = seg?.l100,
                anomaly = seg?.anomaly,
                onClick = { pendingDelete = r },
            )
        }

        item { Spacer(Modifier.height(96.dp)) }
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("知道了") } },
            text = { Text(message) },
        )
    }

    pendingDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记录？") },
            text = { Text("${Fmt.monthDay(r.date)} · ${Fmt.km(r.odometer)} km · ${Fmt.liters(r.liters)} L") },
            confirmButton = {
                TextButton(onClick = { onDelete(r.id); pendingDelete = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun HistoryRow(
    record: FuelRecord,
    l100: Double?,
    anomaly: SegmentAnomaly?,
    onClick: () -> Unit,
) {
    val suspect = anomaly != null
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fmt.monthDay(record.date),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(66.dp),
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${Fmt.km(record.odometer)} km", style = MaterialTheme.typography.bodyMedium)
                        if (!record.isFull) {
                            Spacer(Modifier.width(6.dp))
                            Tag("未加满", Color(0xFF8A939C))
                        }
                        if (record.estimated) {
                            Spacer(Modifier.width(6.dp))
                            Tag("估", Color(0xFFC77700))
                        }
                    }
                    Text(
                        record.station ?: "未记录加油站",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        l100?.let { "${Fmt.l100(it)} L/100km" } ?: if (suspect) "" else "—",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${Fmt.liters(record.liters)} L · ¥${Fmt.yuan(record.amount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (suspect) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    when (anomaly) {
                        SegmentAnomaly.ODOMETER -> "异常 · 核对里程（比上一笔小或相同）"
                        else -> "异常 · 油耗超出生理范围，核对升数或里程"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFC0392B),
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun MoreMenu(onExport: () -> Unit, onImport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "更多操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("导出 CSV") },
                onClick = { open = false; onExport() },
            )
            DropdownMenuItem(
                text = { Text("导入 CSV") },
                onClick = { open = false; onImport() },
            )
        }
    }
}
