package com.carassistant

import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.carassistant.data.db.StationUsage
import com.carassistant.ui.AppUiState
import com.carassistant.ui.AppViewModel
import com.carassistant.ui.history.HistoryScreen
import com.carassistant.ui.home.HomeScreen
import com.carassistant.ui.record.RecordScreen
import com.carassistant.ui.theme.CarAssistantTheme
import com.carassistant.ui.vehicle.VehicleSheet

private const val ROUTE_HOME = "home"
private const val ROUTE_HISTORY = "history"
private const val ROUTE_RECORD = "record"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMaxRefreshRate()
        setContent {
            CarAssistantTheme {
                val vm: AppViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                CarApp(vm, state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来时系统可能按省电策略把窗口降回默认档，再要一次
        requestMaxRefreshRate()
    }

    /**
     * 请求屏幕支持的最高刷新率。
     *
     * 不主动要的话，系统不会给满 —— Android 11 起窗口默认跟随「智能切换」，
     * 国产 ROM 的默认档常是 90，120 的屏因此只跑 90。
     *
     * 三个口子一起提，因为各家认的不是同一个：
     *   preferredDisplayModeId —— 直接指定显示模式，最硬，vivo/OPPO 系认这个
     *   preferredRefreshRate   —— 老接口，小米/华为仍在读
     * setFrameRate 是 Surface 上的方法，窗口层拿不到，所以只走 LayoutParams 这两条。
     *
     * 挑模式时优先保持当前分辨率，避免为了帧率把分辨率让出去。
     */
    private fun requestMaxRefreshRate() {
        // 刷新率只是体验优化：取不到、 ROM 抛异常，都不该让界面起不来
        runCatching {
            val d: Display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            val cur = d.mode
            val modes = d.supportedModes
            if (modes.isEmpty()) return

            val sameSize = modes.filter {
                it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
            }
            val best = (sameSize.ifEmpty { modes.toList() }).maxByOrNull { it.refreshRate } ?: return

            // 已经是最高档就别再写一次：赋值 attributes 会触发一次窗口 relayout
            val a = window.attributes
            if (a.preferredDisplayModeId == best.modeId && a.preferredRefreshRate == best.refreshRate) return

            window.attributes = a.apply {
                preferredDisplayModeId = best.modeId
                preferredRefreshRate = best.refreshRate
            }
        }
    }
}

@Composable
private fun CarApp(vm: AppViewModel, state: AppUiState) {
    val nav = rememberNavController()
    var showVehicleSheet by remember { mutableStateOf(false) }
    /** 导入 / 导出的结果提示，null 表示不显示 */
    var message by remember { mutableStateOf<String?>(null) }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: ROUTE_HOME

    fun topPadding(padding: PaddingValues) = padding.calculateTopPadding() + 8.dp

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (route != ROUTE_RECORD) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = route == ROUTE_HOME,
                        onClick = {
                            nav.navigate(ROUTE_HOME) {
                                popUpTo(ROUTE_HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("首页") },
                    )
                    NavigationBarItem(
                        selected = route == ROUTE_HISTORY,
                        onClick = {
                            nav.navigate(ROUTE_HISTORY) {
                                popUpTo(ROUTE_HOME)
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("历史") },
                    )
                }
            }
        },
        floatingActionButton = {
            if (route != ROUTE_RECORD) {
                ExtendedFloatingActionButton(
                    onClick = {
                        // 一辆车都没有时先加车 —— 直接进录入页没有归属对象（H5）
                        if (state.noVehicle) showVehicleSheet = true else nav.navigate(ROUTE_RECORD)
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(if (state.noVehicle) "添加车辆" else "记一笔") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = ROUTE_HOME, modifier = Modifier.fillMaxSize()) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    state = state,
                    onSwitchVehicle = { showVehicleSheet = true },
                    onAddVehicle = { showVehicleSheet = true },
                    onOpenHistory = {
                        nav.navigate(ROUTE_HISTORY) {
                            popUpTo(ROUTE_HOME)
                            launchSingleTop = true
                        }
                    },
                    contentPadding = PaddingValues(
                        top = topPadding(padding),
                        bottom = padding.calculateBottomPadding(),
                    ),
                )
            }

            composable(ROUTE_HISTORY) {
                HistoryScreen(
                    state = state,
                    contentPadding = PaddingValues(
                        top = topPadding(padding),
                        bottom = padding.calculateBottomPadding(),
                    ),
                    onExport = { uri ->
                        vm.exportTo(uri) { ok ->
                            message = if (ok) "已导出" else "导出失败，换个位置再试"
                        }
                    },
                    onImport = { uri ->
                        vm.importFrom(uri) { summary ->
                            message = when {
                                summary == null -> "导入失败，数据未改动"
                                summary.unrecognized -> "无法识别的文件，数据未改动"
                                else -> buildString {
                                    append("导入 ${summary.imported} 条")
                                    if (summary.duplicates > 0) append("，跳过 ${summary.duplicates} 条重复")
                                    if (summary.badRows > 0) append("，${summary.badRows} 行格式有误")
                                    if (summary.newVehicles > 0) append("\n新建 ${summary.newVehicles} 辆车")
                                }
                            }
                        }
                    },
                    message = message,
                    onDismissMessage = { message = null },
                    onDelete = { vm.deleteRecord(it) },
                )
            }

            composable(ROUTE_RECORD) {
                RecordRoute(
                    vm = vm,
                    state = state,
                    nav = nav,
                    onSwitchVehicle = { showVehicleSheet = true },
                    contentPadding = PaddingValues(
                        top = topPadding(padding),
                        bottom = padding.calculateBottomPadding(),
                    ),
                )
            }
        }
    }

    if (showVehicleSheet) {
        VehicleSheet(
            vehicles = state.vehicles,
            currentId = state.current?.id,
            onSelect = { id ->
                vm.switchVehicle(id)
                showVehicleSheet = false
            },
            onCreate = { name, plate, tank, grade ->
                vm.addVehicle(name, plate, tank, grade)
                showVehicleSheet = false
            },
            onUpdate = { vm.updateVehicle(it) },
            onDismiss = { showVehicleSheet = false },
        )
    }
}

/**
 * 录入页要异步拿上次里程、常用加油站、默认价。
 * 拿到之前不渲染表单 —— 先渲染再回填会让输入框闪一下，还可能把用户刚敲的字冲掉。
 */
@Composable
private fun RecordRoute(
    vm: AppViewModel,
    state: AppUiState,
    nav: NavHostController,
    onSwitchVehicle: () -> Unit,
    contentPadding: PaddingValues,
) {
    val vehicle = state.current
    var loaded by remember(vehicle?.id) { mutableStateOf(false) }
    var lastOdo by remember(vehicle?.id) { mutableStateOf<Int?>(null) }
    var stations by remember(vehicle?.id) { mutableStateOf<List<String>>(emptyList()) }
    var defaultPrice by remember(vehicle?.id) { mutableStateOf(0.0) }

    LaunchedEffect(vehicle?.id) {
        val v = vehicle
        if (v == null) {
            loaded = true
            return@LaunchedEffect
        }
        loaded = false
        lastOdo = vm.lastOdometer(v.id)
        stations = vm.stationUsage(v.id).map(StationUsage::name)
        // 上次实际付的单价 · 内置默认价 · 0（C3：不能编一个数出来）
        defaultPrice = vm.lastPrice(v.id) ?: AppViewModel.FALLBACK_PRICES[v.fuelGrade] ?: 0.0
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize().padding(contentPadding))
        return
    }

    // 中途切车必须重建整个表单：里程属于上一辆车，留着会直接算错区间（H3）
    key(vehicle?.id) {
        RecordScreen(
            vehicle = vehicle,
            vehicles = state.vehicles,
            lastOdometer = lastOdo,
            defaultPrice = defaultPrice,
            stations = stations,
            onSwitchVehicle = onSwitchVehicle,
            onSave = { date, odo, liters, amount, price, full, station, estimated ->
                val id = vehicle?.id
                if (id != null) {
                    vm.saveRecord(id, date, odo, liters, amount, price, full, station, estimated)
                }
                nav.popBackStack()
            },
            contentPadding = contentPadding,
        )
    }
}
