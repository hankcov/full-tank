package com.carassistant.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.carassistant.data.db.AppDatabase
import com.carassistant.data.entity.FuelRecord
import com.carassistant.data.repo.FuelRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 端到端跑一遍「导出 → 清库 → 导入」，判据 G1 / G2 / H7 需要真库才验得准 ——
 * 纯 CsvCodec 只能证明文本能来回，证明不了归车和去重。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryImportExportTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: FuelRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = FuelRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun rec(vehicleId: Long, date: String, odo: Int, liters: Double, amount: Double, station: String? = null) =
        FuelRecord(
            vehicleId = vehicleId,
            date = LocalDate.parse(date),
            odometer = odo,
            liters = liters,
            amount = amount,
            price = amount / liters,
            isFull = true,
            station = station,
        )

    /** G1 + H7：两辆车导出再导入，车名、车牌、记录数、油耗全部复原 */
    @Test
    fun `export then import restores both vehicles`() = runTest {
        val a = repo.addVehicle("我的车", "陕A·8K2L7", 45.0, "92")
        val b = repo.addVehicle("家里的SUV", "陕A·F5D0", 60.0, "95")

        repo.addRecord(rec(a, "2026-05-01", 8200, 45.0, 368.0, "中石化 · 科技路"))
        repo.addRecord(rec(a, "2026-05-28", 8790, 45.1, 368.0, "中石化 · 科技路"))
        repo.addRecord(rec(b, "2026-09-20", 42310, 57.0, 511.29, "中石化 · 长安路"))
        repo.addRecord(rec(b, "2026-10-11", 43510, 56.5, 506.81, "中石化 · 长安路"))

        val metricsA = repo.metricsAt(a, 45.0)
        val csv = repo.exportCsv()

        // 清库后再导入
        db.clearAllTables()
        val summary = repo.importCsv(csv)

        assertEquals(4, summary.imported)
        assertEquals(0, summary.duplicates)

        val vehicles = repo.vehicles()
        assertEquals(2, vehicles.size)
        // H7：车名不能退化成车牌
        assertEquals(setOf("我的车", "家里的SUV"), vehicles.map { it.name }.toSet())

        val restoredA = vehicles.first { it.name == "我的车" }
        assertEquals("陕A·8K2L7", restoredA.plate)
        assertEquals(metricsA.currentL100!!, repo.metricsAt(restoredA.id, 45.0).currentL100!!, 1e-6)

        // 油箱容积跟着回来了，否则重装后续航会静默退回「按上次加油量估」
        assertEquals(45.0, restoredA.tankCapacity, 1e-9)
        assertEquals(60.0, vehicles.first { it.name == "家里的SUV" }.tankCapacity, 1e-9)
    }

    /**
     * 车在库里已有、CSV 里也有油箱：本地没填才补，本地填过就不覆盖。
     * 否则导一份旧备份就能把用户刚改对的容积改回去。
     */
    @Test
    fun `import only fills a missing tank`() = runTest {
        val a = repo.addVehicle("我的车", "陕A·8K2L7", 0.0, "92")
        repo.addRecord(rec(a, "2026-10-18", 12890, 44.0, 374.0, null))

        // 一份带油箱容积的 CSV（本地油箱是空的，应该补上）
        val withTank = buildString {
            append("vehicle,vehicle_name,date,odometer,liters,amount,price,is_full,station,estimated,tank\r\n")
            append("陕A·8K2L7,我的车,2026-10-19,12950,44,374,8.49,1,,0,45\r\n")
        }
        repo.importCsv(withTank)
        assertEquals(45.0, repo.vehicles().first { it.id == a }.tankCapacity, 1e-9)

        // 本地改成 5.5 后再导同一份，不能被 45 盖掉
        repo.updateVehicle(repo.vehicles().first { it.id == a }.copy(tankCapacity = 5.5))
        repo.importCsv(withTank)
        assertEquals(5.5, repo.vehicles().first { it.id == a }.tankCapacity, 1e-9)
    }

    /** G2：同车同日期同里程才算重复；两辆车同一天同一里程不冲突 */
    @Test
    fun `duplicate detection is per vehicle`() = runTest {
        val a = repo.addVehicle("A", "陕A·1111", 45.0, "92")
        val b = repo.addVehicle("B", "陕A·2222", 45.0, "92")

        repo.addRecord(rec(a, "2026-10-01", 1000, 40.0, 340.0))
        repo.addRecord(rec(b, "2026-10-01", 1000, 40.0, 340.0)) // 同日期同里程，但是另一辆车

        val csv = repo.exportCsv()
        val summary = repo.importCsv(csv)

        assertEquals(0, summary.imported)
        assertEquals(2, summary.duplicates)
        assertEquals(1, repo.records(a).size)
        assertEquals(1, repo.records(b).size)
    }

    /** G6：非 CSV 内容不改动任何现有数据 */
    @Test
    fun `bad import leaves data untouched`() = runTest {
        val a = repo.addVehicle("我的车", "陕A·8K2L7", 45.0, "92")
        repo.addRecord(rec(a, "2026-10-01", 1000, 40.0, 340.0))

        val summary = repo.importCsv("这不是表格\n随便几行")

        assertTrue(summary.unrecognized)
        assertEquals(1, repo.records(a).size)
        assertEquals(1, repo.vehicles().size)
    }

    /** 导入含逗号的站名后仍原样保留（G3 走真库） */
    @Test
    fun `station with comma survives db round trip`() = runTest {
        val a = repo.addVehicle("我的车", "陕A·8K2L7", 45.0, "92")
        repo.addRecord(rec(a, "2026-10-01", 1000, 40.0, 340.0, "某站, 带逗号"))

        val csv = repo.exportCsv()
        db.clearAllTables()
        repo.importCsv(csv)

        val v = repo.vehicles().single()
        assertEquals("某站, 带逗号", repo.records(v.id).single().station)
    }

    /** 空车时指标全空，不抛异常（H6 的库层验证） */
    @Test
    fun `metrics for empty vehicle do not throw`() = runTest {
        val a = repo.addVehicle("新车", "", 45.0, "92")
        val m = repo.metricsAt(a, 0.0, LocalDate.parse("2026-10-21"))
        assertTrue(m.isEmpty)
        assertNotNull(m)
        assertEquals(0, m.recordCount)
    }
}
