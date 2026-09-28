package com.wmserp.app.presentation.components.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wmserp.app.R
import java.util.Locale

data class BarEntry(val label: String, val value: Double, val color: Color? = null)

/** Vertical bar chart drawn with plain composables (no canvas text needed). */
@Composable
fun BarChart(
    entries: List<BarEntry>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    chartHeight: Dp = 160.dp,
    valueFormatter: (Double) -> String = { if (it == it.toLong().toDouble()) it.toLong().toString() else "%.1f".format(Locale.US, it) },
) {
    val max = entries.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            entries.forEach { entry ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(valueFormatter(entry.value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    val fraction = (entry.value / max).toFloat().coerceIn(0.04f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .background(entry.color ?: barColor),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            entries.forEach { entry ->
                Text(
                    entry.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Day x hour-block intensity grid. */
@Composable
fun HeatmapGrid(
    dayLabels: List<String>,
    blockLabels: List<String>,
    cells: List<List<Int>>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    emptyColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val max = cells.maxOfOrNull { row -> row.maxOrNull() ?: 0 }?.takeIf { it > 0 } ?: 1
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(36.dp))
            blockLabels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        cells.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dayLabels.getOrElse(rowIndex) { "" },
                    modifier = Modifier.width(36.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                row.forEach { value ->
                    val alpha = if (value == 0) 0f else (0.25f + 0.75f * (value.toFloat() / max)).coerceIn(0.25f, 1f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .aspectRatio(1.4f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (value == 0) emptyColor else color.copy(alpha = alpha)),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_less), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(0f, 0.35f, 0.6f, 0.85f, 1f).forEach { a ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (a == 0f) emptyColor else color.copy(alpha = a)),
                )
            }
            Text(stringResource(R.string.common_more), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class StackedSegment(val label: String, val value: Double, val color: Color)

/** Horizontal stacked distribution bar with a legend, used for stock aging buckets. */
@Composable
fun StackedDistributionBar(segments: List<StackedSegment>, modifier: Modifier = Modifier, height: Dp = 18.dp) {
    val total = segments.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            segments.filter { it.value > 0 }.forEach { seg ->
                Box(
                    modifier = Modifier
                        .weight((seg.value / total).toFloat().coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(seg.color),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            segments.forEach { seg ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(seg.color))
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(seg.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (seg.value == seg.value.toLong().toDouble()) seg.value.toLong().toString() else "%.1f".format(Locale.US, seg.value),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** A simple progress row (label, value and a proportional bar). */
@Composable
fun ProgressRow(label: String, fraction: Float, valueText: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}
