package com.carassistant.ui.record

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carassistant.data.entity.Vehicle
import com.carassistant.ui.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

enum class EditField { NONE, AMOUNT, LITERS, PRICE }

/**
 * 录入页。
 *
 * 三个数（金额 / 升数 / 单价）只知道两个就能算第三个，但**不能三个都当输入框各算各的** ——
 * 那样用户改一个，另两个互相覆盖，数字会自己跳。
 * 所以用 editField 记住「最后改的是哪个」，只让它驱动另外两个。
 *
 * 现实场景：很多人只记总价（付了 374），不知道加了多少升。
 * 那时升数由 金额 ÷ 单价 反推，标记 estimated，历史里显示「估」—— 是推算值就得标出来。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordScreen(
    vehicle: Vehicle?,
    vehicles: List<Vehicle>,
    lastOdometer: Int?,
    defaultPrice: Double,
    stations: List<String>,
    onSwitchVehicle: () -> Unit,
    onSave: (date: LocalDate, odo: Int, liters: Double, amount: Double, price: Double, full: Boolean, station: String?, estimated: Boolean) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var odoText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var litersText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf(defaultPrice.takeIf { it > 0 }?.let { Fmt.price(it) } ?: "") }
    var isFull by remember { mutableStateOf(true) }
    var station by remember { mutableStateOf("") }
    var editField by remember { mutableStateOf(EditField.NONE) }

    val price = priceText.toDoubleOrNull() ?: 0.0
    val amount = amountText.toDoubleOrNull() ?: 0.0
    val liters = litersText.toDoubleOrNull() ?: 0.0
    val odo = odoText.toDoubleOrNull()?.toInt()

    // 单价变了就重算「估算的那一侧」，但不动用户手填的数（C2）
    fun recomputeFromAmount() {
        if (price > 0 && amount > 0) litersText = String.format("%.2f", amount / price)
    }
    fun recomputeFromLiters() {
        if (price > 0 && liters > 0) amountText = String.format("%.2f", liters * price)
    }
    fun recomputeFromPrice() {
        if (price > 0 && amount > 0) {
            litersText = String.format("%.2f", amount / price)
        } else if (price > 0 && liters > 0) {
            amountText = String.format("%.2f", liters * price)
        }
    }

    val estimated = editField == EditField.AMOUNT && litersText.isNotBlank()
    val canSave = odo != null && odo > 0 && (amount > 0 || liters > 0) && vehicle != null
    val odoRollback = odo != null && lastOdometer != null && odo <= lastOdometer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        // 车
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onSwitchVehicle),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("记到", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        vehicle?.name ?: "添加车辆",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "切换车辆", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(10.dp))

        // 日期
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { showDatePicker = true },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("日期", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        NumberField(
            value = odoText,
            onValueChange = { odoText = it.filter { c -> c.isDigit() } },
            label = "总里程",
            unit = "km",
            supporting = when {
                odoRollback && lastOdometer != null -> "上次记录是 ${Fmt.km(lastOdometer ?: 0)} km，这个数比它还小"
                lastOdometer != null -> "上次记录 ${Fmt.km(lastOdometer ?: 0)} km"
                else -> null
            },
            supportingIsError = odoRollback,
        )

        Spacer(Modifier.height(10.dp))

        NumberField(
            value = amountText,
            onValueChange = {
                amountText = it.onlyDecimal()
                editField = EditField.AMOUNT
                recomputeFromAmount()
            },
            label = "金额",
            unit = "元",
        )

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                NumberField(
                    value = litersText,
                    onValueChange = {
                        litersText = it.onlyDecimal()
                        editField = EditField.LITERS
                        recomputeFromLiters()
                    },
                    label = "升数",
                    unit = "L",
                    trailingTag = if (estimated) "估" else null,
                )
            }
            Box(Modifier.weight(1f)) {
                NumberField(
                    value = priceText,
                    onValueChange = {
                        priceText = it.onlyDecimal()
                        editField = EditField.PRICE
                        recomputeFromPrice()
                    },
                    label = "单价",
                    unit = "元/L",
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("加满", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "加满才能算出这一段油耗",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = isFull, onCheckedChange = { isFull = it })
            }
        }

        Spacer(Modifier.height(10.dp))

        // 加油站：只列历史用过的，不给预设名单（E2）
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = station,
                    onValueChange = { station = it },
                    label = { Text("加油站") },
                    placeholder = { Text(if (stations.isEmpty()) "还没有历史记录，手动填" else "选一个或手动填") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (stations.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        stations.take(5).forEach { s ->
                            Text(
                                s,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { station = s }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                onSave(
                    date,
                    odo ?: 0,
                    liters,
                    if (amount > 0) amount else liters * price,
                    price,
                    isFull,
                    station.ifBlank { null },
                    estimated,
                )
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("保存", fontSize = 16.sp)
        }

        if (vehicle == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "先添加一辆车",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(40.dp))
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    unit: String,
    supporting: String? = null,
    supportingIsError: Boolean = false,
    trailingTag: String? = null,
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                isError = supportingIsError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (trailingTag != null) {
                            Text(
                                trailingTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (supportingIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** 只保留数字和一个小数点，避免用户敲出 "1.2.3" 这种无法解析的东西 */
private fun String.onlyDecimal(): String {
    val filtered = filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    if (firstDot < 0) return filtered
    return filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "")
}
