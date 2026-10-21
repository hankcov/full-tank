package com.carassistant.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carassistant.domain.Segment
import kotlin.math.max
import kotlin.math.min

/**
 * 油耗趋势折线。
 *
 * 用 Canvas 自绘而不是引图表库：只有一条线，引库要多几十 KB 和一层配置，
 * 而且样式得跟着主题改，反而更麻烦。
 *
 * y 轴不从 0 开始 —— 油耗都在 5~10 之间，从 0 起会把差异压成一条平线，
 * 用户看不出波动。但必须标出上下界，否则「跳得厉害」是错觉。
 */
@Composable
fun TrendChart(trend: List<Segment>, modifier: Modifier = Modifier) {
    val points = trend.mapNotNull { s -> s.l100?.let { it to s } }
    if (points.size < 2) {
        Box(modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text(
                "至少两次加满才有趋势",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val values = points.map { it.first }
    val rawMin = values.min()
    val rawMax = values.max()
    // 上下各留 15% 余量，否则最高最低点会贴边被裁掉
    val pad = max((rawMax - rawMin) * 0.15, 0.15)
    val lo = rawMin - pad
    val hi = rawMax + pad

    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp)) {
            val w = size.width
            val h = size.height
            val left = 0f
            val right = w
            val top = 6f
            val bottom = h - 6f

            fun y(v: Double): Float = (bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top))
            fun x(i: Int): Float =
                if (points.size == 1) (left + right) / 2 else left + (right - left) * i / (points.size - 1).toFloat()

            // 三条横向参考线，不给刻度数字 —— 数字会跟大字号抢注意力
            for (g in 0..2) {
                val gy = top + (bottom - top) * g / 2f
                drawLine(gridColor, Offset(left, gy), Offset(right, gy), strokeWidth = 1f)
            }

            val path = Path()
            points.forEachIndexed { i, (v, _) ->
                val px = x(i)
                val py = y(v)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, lineColor, style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round))

            // 面积渐变会让折线显得更重，这里只画点
            points.forEachIndexed { i, (v, _) ->
                drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(x(i), y(v)))
            }

            // 最高 / 最低点描实心大点，方便一眼看出极值在哪
            val iMax = values.indexOf(rawMax)
            val iMin = values.indexOf(rawMin)
            drawCircle(lineColor.copy(alpha = 0.25f), radius = 6.dp.toPx(), center = Offset(x(iMax), y(rawMax)))
            drawCircle(lineColor.copy(alpha = 0.25f), radius = 6.dp.toPx(), center = Offset(x(iMin), y(rawMin)))
        }

        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Text("最低 ${"%.2f".format(rawMin)}", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text("最高 ${"%.2f".format(rawMax)}", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}
