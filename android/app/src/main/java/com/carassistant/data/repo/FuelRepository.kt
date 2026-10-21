package com.carassistant.data.repo

import androidx.room.withTransaction
import com.carassistant.data.csv.CsvCodec
import com.carassistant.data.csv.CsvRow
import com.carassistant.data.db.AppDatabase
import com.carassistant.data.db.StationUsage
import com.carassistant.data.entity.FuelRecord
import com.carassistant.data.entity.Vehicle
import com.carassistant.data.entity.toEntry
import com.carassistant.domain.FuelEntry
import com.carassistant.domain.MetricsCalculator
import com.carassistant.domain.VehicleMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate

data class ImportSummary(
    val imported: Int = 0,
    val duplicates: Int = 0,
    val badRows: Int = 0,
    val newVehicles: Int = 0,
    val unrecognized: Boolean = false,
) {
    val touchedAnything: Boolean get() = imported > 0 || newVehicles > 0
}

/**
 * 唯一的数据入口。UI 与 ViewModel 都不直接碰 DAO。
 *
 * 这里最关键的一条：**任何按里程的运算都先在仓库层按 vehicleId 收窄**，
 * 不给调用方「把全库记录传进算法」的机会。
 */
class FuelRepository(private val db: AppDatabase) {

    private val vehicleDao = db.vehicleDao()
    private val recordDao = db.fuelRecordDao()

    // ---- 车辆 ----

    fun observeVehicles(): Flow<List<Vehicle>> = vehicleDao.observeAll()

    suspend fun vehicles(): List<Vehicle> = vehicleDao.getAll()

    suspend fun addVehicle(
        name: String,
        plate: String = "",
        tankCapacity: Double = 0.0,
        fuelGrade: String = "92",
    ): Long = vehicleDao.insert(
        Vehicle(name = name.trim(), plate = plate.trim(), tankCapacity = tankCapacity, fuelGrade = fuelGrade)
    )

    suspend fun updateVehicle(v: Vehicle) = vehicleDao.update(v)

    // ---- 记录 ----

    /**
     * 单辆车的当前指标。切换车辆时重新订阅，天然跟着切。
     *
     * tankCapacity 从调用方传进来、而不是这里查库：改了油箱容积要能立刻重算，
     * 查库会读到旧的（车辆表更新与记录表观察是两条流，时序没保证）。
     */
    fun observeMetrics(vehicleId: Long, tankCapacity: Double): Flow<VehicleMetrics> =
        recordDao.observeByVehicle(vehicleId).map { list ->
            MetricsCalculator.compute(list.map { it.toEntry() }, tankCapacity)
        }

    fun observeRecords(vehicleId: Long): Flow<List<FuelRecord>> =
        recordDao.observeByVehicle(vehicleId)

    /** 首页 + 历史 + 趋势一起用：一次订阅拿到记录和算好的指标 */
    fun observeVehicleData(
        vehicleId: Long,
        tankCapacity: Double,
    ): Flow<Pair<List<FuelRecord>, VehicleMetrics>> =
        recordDao.observeByVehicle(vehicleId).map { list ->
            list to MetricsCalculator.compute(list.map { it.toEntry() }, tankCapacity)
        }

    suspend fun records(vehicleId: Long): List<FuelRecord> = recordDao.getByVehicle(vehicleId)

    suspend fun stationUsage(vehicleId: Long): List<StationUsage> =
        recordDao.stationUsage(vehicleId)

    /** 上次该车记录的实付单价，用于录入页预填（C2：手填值优先于接口价） */
    suspend fun lastPrice(vehicleId: Long): Double? {
        val list = recordDao.getByVehicle(vehicleId)
        return list.lastOrNull { it.price > 0 }?.price
    }

    /**
     * 上次加油的里程。换车后录入页要靠它校验新里程是否倒退，
     * 但**不预填**到输入框 —— 预填一个接近真实的数字，用户很容易忘记改。
     */
    suspend fun lastOdometer(vehicleId: Long): Int? = recordDao.maxOdometer(vehicleId)

    suspend fun addRecord(r: FuelRecord): Long = recordDao.insert(r)
    suspend fun updateRecord(r: FuelRecord) = recordDao.update(r)
    suspend fun deleteRecord(id: Long) = recordDao.deleteById(id)

    // ---- 导出 / 导入 ----

    suspend fun exportCsv(): String {
        val vehicles = vehicleDao.getAll().associateBy { it.id }
        val records = recordDao.getAll()
        val rows = records.map { r ->
            val v = vehicles[r.vehicleId]
            CsvRow(
                vehiclePlate = v?.plate ?: "",
                vehicleName = v?.name ?: "",
                vehicleTank = v?.tankCapacity ?: 0.0,
                date = r.date,
                odometer = r.odometer,
                liters = r.liters,
                amount = r.amount,
                price = r.price,
                isFull = r.isFull,
                station = r.station,
                estimated = r.estimated,
            )
        }
        return CsvCodec.export(rows)
    }

    /**
     * 导入。全程一个事务：中途任何异常都回滚，不留半份数据（G6）。
     * 重复按「同车 + 同日期 + 同里程」判定（G2）。
     */
    suspend fun importCsv(text: String): ImportSummary = db.withTransaction {
        val parsed = CsvCodec.parse(text)
        if (!parsed.ok) return@withTransaction ImportSummary(unrecognized = true)

        val known = vehicleDao.getAll().toMutableList()
        var imported = 0
        var dup = 0
        var created = 0

        for (row in parsed.rows) {
            val vehicleId = resolveVehicle(row, known) { created++ }

            val exists = recordDao.countDuplicate(vehicleId, row.date, row.odometer) > 0
            if (exists) {
                dup++
                continue
            }
            recordDao.insert(
                FuelRecord(
                    vehicleId = vehicleId,
                    date = row.date,
                    odometer = row.odometer,
                    liters = row.liters,
                    amount = row.amount,
                    price = row.price,
                    isFull = row.isFull,
                    station = row.station,
                    estimated = row.estimated,
                )
            )
            imported++
        }

        ImportSummary(
            imported = imported,
            duplicates = dup,
            badRows = parsed.skipped,
            newVehicles = created,
            unrecognized = false,
        )
    }

    /**
     * 归车顺序：
     * 1. 车牌命中 → 这辆车
     * 2. 车牌空 → 按车名命中
     * 3. 都没有 → 新建（名字取 vehicle_name，缺失用车牌，再缺失用「车辆 N」）
     *
     * 车牌为空且库里只有一辆车时归到那一辆 —— 用户单车时代导出的旧文件没有车牌列，
     * 强行新建会把他原来的车一分为二。
     */
    private suspend fun resolveVehicle(
        row: CsvRow,
        known: MutableList<Vehicle>,
        onCreate: () -> Unit,
    ): Long {
        if (row.vehiclePlate.isNotBlank()) {
            known.firstOrNull { it.plate == row.vehiclePlate }?.let { return adoptTank(it, row, known) }
            val v = Vehicle(
                name = row.vehicleName.ifBlank { row.vehiclePlate },
                plate = row.vehiclePlate,
                tankCapacity = row.vehicleTank,
            )
            val id = vehicleDao.insert(v)
            known += v.copy(id = id)
            onCreate()
            return id
        }

        if (row.vehicleName.isNotBlank()) {
            known.firstOrNull { it.name == row.vehicleName }?.let { return adoptTank(it, row, known) }
        }

        known.firstOrNull()?.let { return adoptTank(it, row, known) }

        val v = Vehicle(name = row.vehicleName.ifBlank { "我的车" }, tankCapacity = row.vehicleTank)
        val id = vehicleDao.insert(v)
        known += v.copy(id = id)
        onCreate()
        return id
    }

    /**
     * 已有车遇到 CSV 里的油箱容积：**只在本地没填时补上**。
     * 本地填过就是用户手动设过的，导入不能覆盖它 —— 否则导一份旧备份
     * 就能把用户刚改对的容积改回去。
     */
    private suspend fun adoptTank(v: Vehicle, row: CsvRow, known: MutableList<Vehicle>): Long {
        if (v.tankCapacity <= 0 && row.vehicleTank > 0) {
            val updated = v.copy(tankCapacity = row.vehicleTank)
            vehicleDao.update(updated)
            val i = known.indexOfFirst { it.id == v.id }
            if (i >= 0) known[i] = updated
        }
        return v.id
    }

    /** 趋势数据：只给可信区间，按日期升序 */
    suspend fun trendPoints(vehicleId: Long): List<FuelEntry> =
        recordDao.getByVehicle(vehicleId).map { it.toEntry() }

    suspend fun metricsAt(vehicleId: Long, tankCapacity: Double, today: LocalDate = LocalDate.now()): VehicleMetrics =
        MetricsCalculator.compute(recordDao.getByVehicle(vehicleId).map { it.toEntry() }, tankCapacity, today)
}
