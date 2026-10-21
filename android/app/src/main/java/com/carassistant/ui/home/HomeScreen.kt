package com.carassistant.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import com.carassistant.domain.RangeBasis
import com.carassistant.domain.Segment
import com.carassistant.domain.VehicleMetrics
import com.carassistant.ui.AppUiState
import com.carassistant.ui.Fmt

/**
 * 首页。四层固定顺序：核心（当前油耗）/ 事实（上次加油）/ 建议（续航）/ 趋势。
 * 不因数据缺失重排 —— 位置一跳动，用户每次都要重新找（B5）。
 */
@Composable
fun HomeScreen(
    state: AppUiState,
    onSwitchVehicle: () -> Unit,
    onAddVehicle: () -> Unit,
    onOpenHistory: () -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
    ) {
        item {
            VehicleHeader(
                name = state.current?.name ?: "添加车辆",
                plate = state.current?.plate ?: "还没有车",
                hasVehicle = state.current != null,
                onClick = { if (state.current == null) onAddVehicle() else onSwitchVehicle() },
                onOpenHistory = onOpenHistory,
            )
        }

        if (state.noVehicle) {
            item { EmptyVehicleCard(onAddVehicle) }
            return@LazyColumn
        }

        item { CurrentConsumptionCard(state.metrics) }

        if (state.metrics.isEmpty) {
            item {
                Text(
                    "这辆车还没有记录，点下方「记一笔」开始",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        } else {
            item { LastRefuelCard(state.metrics) }
            item { TotalsCard(state.metrics) }
            if (state.metrics.hasTrustedSegment) {
                item { RangeCard(state.metrics, state.current?.tankCapacity ?: 0.0) }
            }
            if (state.metrics.trend.isNotEmpty()) item { TrendSection(state.metrics.trend) }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun VehicleHeader(
    name: String,
    plate: String,
    hasVehicle: Boolean,
    onClick: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (hasVehicle) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "切换车辆",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Text(
                plate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "历史",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenHistory).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** 核心层：只放一个数。页面上唯一的大字号，其余都是辅助 */
@Composable
private fun CurrentConsumptionCard(m: VehicleMetrics) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "当前油耗",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    m.currentL100?.let { Fmt.l100(it) } ?: "—",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (m.currentL100 == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "L/100km",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            val delta = m.deltaFromPrev
            if (delta != null) {
                Spacer(Modifier.height(2.dp))
                // 涨是坏（多烧油），用暖色；降是绿色。跟股价相反，这里红=费钱
                val worse = delta > 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Fmt.sign(delta)} ${Fmt.l100(kotlin.math.abs(delta))}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (worse) Color(0xFFC0392B) else Color(0xFF2E7D46),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("比上一箱", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (m.currentL100 == null && m.recordCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "还没有可信的油耗区间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 事实层：上一次发生了什么，不加任何解释 */
@Composable
private fun LastRefuelCard(m: VehicleMetrics) {
    val last = m.lastRecord ?: return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("上次加油", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Fmt.monthDay(last.date)} · ${Fmt.ago(last.date)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${Fmt.liters(last.liters)} L",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "¥${Fmt.yuan(last.amount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TotalsCard(m: VehicleMetrics) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatCell("累计行驶", Fmt.km(m.totalDistance), "km", Modifier.weight(1f))
                StatCell("累计油费", "¥${Fmt.yuan(m.totalCost)}", "", Modifier.weight(1f))
                StatCell("累计平均", m.avgL100?.let { Fmt.l100(it) } ?: "—", "L/100km", Modifier.weight(1f))
                StatCell("加油次数", m.recordCount.toString(), "次", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/**
 * 建议层：唯一的推算值，措辞用「预计」，不说「该去加了」—— 那是命令，不是建议。
 *
 * 主次：预计加油里程是主（用户真正要的那个数），续航是小字（它只是这个数的来处）。
 * 之前反过来，结果每个看到的人都先读到一个能自己心算出来的数。
 */
@Composable
private fun RangeCard(m: VehicleMetrics, tankCapacity: Double) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            val fg = MaterialTheme.colorScheme.onPrimaryContainer
            Text(
                "预计加油里程",
                style = MaterialTheme.typography.labelMedium,
                color = fg.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    m.expectedRefuelOdometer?.let { Fmt.km(it) } ?: "—",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "km",
                    style = MaterialTheme.typography.bodySmall,
                    color = fg.copy(alpha = 0.72f),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                rangeBasisLine(m, tankCapacity),
                style = MaterialTheme.typography.bodySmall,
                color = fg.copy(alpha = 0.68f),
            )
        }
    }
}

/** 这个数是怎么来的。写出来，用户才知道误差有多大、该不该去填油箱 */
private fun rangeBasisLine(m: VehicleMetrics, tankCapacity: Double): String {
    val range = m.rangeKm ?: return "暂无续航估算"
    return when (m.rangeBasis) {
        RangeBasis.TANK -> "满箱 ${Fmt.trim(tankCapacity)} L 能跑约 $range km"
        RangeBasis.LAST_FILL -> {
            val liters = m.lastRecord?.liters
            if (liters != null && liters > 0) {
                "续航约 $range km（按上次加油 ${Fmt.liters(liters)} L 估）· 填油箱容积更准"
            } else {
                "续航约 $range km"
            }
        }
    }
}

/** 趋势层：默认折叠。首页的主问题是「现在多少」，趋势是第二个问题 */
@Composable
private fun TrendSection(trend: List<Segment>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("趋势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("近 ${trend.size} 次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    TrendChart(trend)
                }
            }
        }
    }
}

@Composable
private fun EmptyVehicleCard(onAddVehicle: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onAddVehicle),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还一辆车都没有", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "先添加一辆车，再开始记账",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("添加车辆", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
