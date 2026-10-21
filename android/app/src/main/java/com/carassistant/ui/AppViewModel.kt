package com.carassistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carassistant.data.csv.CsvCodec
import com.carassistant.data.db.AppDatabase
import com.carassistant.data.db.StationUsage
import com.carassistant.data.entity.FuelRecord
import com.carassistant.data.entity.Vehicle
import com.carassistant.data.entity.toEntry
import com.carassistant.data.prefs.Prefs
import com.carassistant.data.repo.FuelRepository
import com.carassistant.data.repo.ImportSummary
import com.carassistant.domain.FuelMath
import com.carassistant.domain.Segment
import com.carassistant.domain.VehicleMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class AppUiState(
    val vehicles: List<Vehicle> = emptyList(),
    val current: Vehicle? = null,
    val metrics: VehicleMetrics = VehicleMetrics.EMPTY,
    val records: List<FuelRecord> = emptyList(),
    /** 按终点记录 id 索引，历史列表靠它标出异常行 */
    val segments: Map<Long, Segment> = emptyMap(),
    val loaded: Boolean = false,
) {
    /** 一辆车都没有：首页要显示「添加车辆」而不是录入入口（H5） */
    val noVehicle: Boolean get() = loaded && vehicles.isEmpty()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FuelRepository(AppDatabase.get(app))
    private val prefs = Prefs(app)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<AppUiState> =
        combine(repo.observeVehicles(), prefs.currentVehicleId) { vs, savedId ->
            // 保存的车被删了就回落第一辆；一辆都没有则进入零车辆状态，不崩（H5）
            val current = vs.firstOrNull { it.id == savedId } ?: vs.firstOrNull()
            vs to current
        }.flatMapLatest { (vs, current) ->
            if (current == null) {
                flowOf(AppUiState(vehicles = vs, current = null, loaded = true))
            } else {
                repo.observeVehicleData(current.id, current.tankCapacity).map { (records, metrics) ->
                    AppUiState(
                        vehicles = vs,
                        current = current,
                        metrics = metrics,
                        records = records,
                        segments = FuelMath.segmentsByEndId(records.map { r -> r.toEntry() }),
                        loaded = true,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    // ---- 车辆 ----

    fun switchVehicle(id: Long) = viewModelScope.launch { prefs.setCurrentVehicle(id) }

    fun addVehicle(name: String, plate: String, tank: Double, grade: String, onDone: (Long) -> Unit = {}) =
        viewModelScope.launch {
            val id = repo.addVehicle(name, plate, tank, grade)
            prefs.setCurrentVehicle(id) // 新车加完直接切过去，省得用户再点一次
            onDone(id)
        }

    /** 改车名、车牌、油箱、标号。油箱容积改动会立刻反映到续航（车辆表是 state 的上游） */
    fun updateVehicle(v: Vehicle) = viewModelScope.launch { repo.updateVehicle(v) }

    // ---- 记录 ----

    fun saveRecord(
        vehicleId: Long,
        date: LocalDate,
        odometer: Int,
        liters: Double,
        amount: Double,
        price: Double,
        isFull: Boolean,
        station: String?,
        estimated: Boolean,
        onDone: () -> Unit = {},
    ) = viewModelScope.launch {
        repo.addRecord(
            FuelRecord(
                vehicleId = vehicleId,
                date = date,
                odometer = odometer,
                liters = liters,
                amount = amount,
                price = price,
                isFull = isFull,
                station = station,
                estimated = estimated,
            )
        )
        onDone()
    }

    fun deleteRecord(id: Long) = viewModelScope.launch { repo.deleteRecord(id) }

    suspend fun lastOdometer(vehicleId: Long): Int? = repo.lastOdometer(vehicleId)

    suspend fun lastPrice(vehicleId: Long): Double? = repo.lastPrice(vehicleId)

    suspend fun stationUsage(vehicleId: Long): List<StationUsage> = repo.stationUsage(vehicleId)

    // ---- 导入导出 ----

    fun exportTo(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            val csv = repo.exportCsv()
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入")
            }
            true
        }.getOrDefault(false)
        onResult(ok)
    }

    fun importFrom(uri: Uri, onResult: (ImportSummary?) -> Unit) = viewModelScope.launch {
        val summary = runCatching {
            val text = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: error("无法读取")
            }
            repo.importCsv(text)
        }.getOrNull()
        onResult(summary)
    }

    companion object {
        /** 默认回落到内置价，标记 fallback，等联网成功后覆盖 */
        val FALLBACK_PRICES = mapOf("92" to 7.85, "95" to 8.35, "98" to 9.15)
    }
}
