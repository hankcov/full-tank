package com.carassistant.ui.vehicle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.carassistant.data.entity.Vehicle
import com.carassistant.ui.Fmt

/**
 * 车辆切换 + 新建，共用一个底部弹层。
 *
 * 不单开一页：切车是高频小动作，跳页面会打断当前正在做的事（录入填一半去切车，回来页面就没了）。
 * 油价、加油站也用同一个容器，靠 screen 状态区分，避免三层 sheet 叠在一起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleSheet(
    vehicles: List<Vehicle>,
    currentId: Long?,
    onSelect: (Long) -> Unit,
    onCreate: (name: String, plate: String, tank: Double, grade: String) -> Unit,
    onUpdate: (Vehicle) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var adding by remember { mutableStateOf(vehicles.isEmpty()) }
    var editing by remember { mutableStateOf<Vehicle?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(
                when {
                    editing != null -> "编辑车辆"
                    adding -> "添加车辆"
                    else -> "切换车辆"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            val target = editing
            when {
                target != null -> VehicleForm(
                    initial = target,
                    onCancel = { editing = null },
                    onSubmit = { name, plate, tank, grade ->
                        onUpdate(target.copy(name = name, plate = plate, tankCapacity = tank, fuelGrade = grade))
                        editing = null
                    },
                )

                !adding -> {
                    vehicles.forEach { v ->
                        val selected = v.id == currentId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(v.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(
                                Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            v.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                        if (selected) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "当前",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    Text(
                                        listOfNotNull(
                                            v.plate.ifBlank { null },
                                            "${v.fuelGrade}#",
                                            tankLabel(v.tankCapacity),
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // 铅笔单独一个 40dp 触控区：整行是「切车」，别让两件事共用一次点击
                                IconButton(onClick = { editing = v }, modifier = Modifier.size(40.dp)) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "编辑${v.name}",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "+ 添加车辆",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { adding = true }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }

                else -> VehicleForm(
                    initial = null,
                    onCancel = if (vehicles.isEmpty()) null else ({ adding = false }),
                    onSubmit = { name, plate, tank, grade ->
                        onCreate(name, plate, tank, grade)
                    },
                )
            }
        }
    }
}

/** 油箱没标就让这件事在界面上可见，别让用户以为 0 是 0 升 */
private fun tankLabel(tank: Double): String =
    if (tank > 0) "油箱 ${Fmt.trim(tank)} L" else "油箱未填"

@Composable
private fun VehicleForm(
    initial: Vehicle?,
    onCancel: (() -> Unit)?,
    onSubmit: (String, String, Double, String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var plate by remember(initial) { mutableStateOf(initial?.plate ?: "") }
    var tank by remember(initial) {
        mutableStateOf(initial?.tankCapacity?.takeIf { it > 0 }?.let { Fmt.trim(it) } ?: "")
    }
    var grade by remember(initial) { mutableStateOf(initial?.fuelGrade ?: "92") }

    Column {
        if (initial != null) {
            Text(
                "只改车辆信息，已有加油记录不受影响",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称") },
            placeholder = { Text("比如 我的车") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = plate,
            onValueChange = { plate = it },
            label = { Text("车牌（选填）") },
            placeholder = { Text("导入时靠它认车") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = tank,
                onValueChange = { v -> tank = v.filter { it.isDigit() || it == '.' } },
                label = { Text("油箱 (L，选填)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = {
                    Text(if (tank.toDoubleOrNull()?.let { it > 0 } == true) "按它算满箱续航" else "不填则按上次加油量估算")
                },
                modifier = Modifier.weight(1.4f),
            )
            OutlinedTextField(
                value = grade,
                onValueChange = { grade = it },
                label = { Text("标号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onCancel != null) {
                Surface(
                    modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onCancel),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSubmit(
                            name.trim(),
                            plate.trim(),
                            tank.toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0,
                            grade.ifBlank { "92" },
                        )
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(2f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("保存") }
        }
    }
}
