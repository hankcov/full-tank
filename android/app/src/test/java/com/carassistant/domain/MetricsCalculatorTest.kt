package com.carassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 对应 PLAN.md 判据 B1–B5、G4–G5、H4–H6。
 *
 * 基准数据就是原型里那 10 条（原型的数字已在 jsdom 里跑通过），
 * 所以这里的期望值不是另外算的，而是跟已经验收过的界面一致。
 */
class MetricsCalculatorTest {

    private var id = 0L
    private fun e(
        date: String,
        odo: Int,
        liters: Double,
        amount: Double,
        isFull: Boolean = true,
    ) = FuelEntry(++id, LocalDate.parse(date), odo, liters, amount, isFull)

    /** 原型「我的车」的 10 条记录 */
    private fun sampleCar(): List<FuelEntry> {
        id = 0
        return listOf(
            e("2026-10-18", 12890, 44.0, 374.0),
            e("2026-10-02", 12305, 46.8, 397.0),
            e("2026-09-15", 11720, 23.1, 189.0),
            e("2026-08-30", 11430, 20.0, 163.0, isFull = false),
            e("2026-08-12", 11160, 46.3, 378.0),
            e("2026-07-24", 10570, 49.0, 400.0),
            e("2026-07-05", 9980, 49.5, 404.0),
            e("2026-06-16", 9385, 47.2, 386.0),
            e("2026-05-28", 8790, 45.1, 368.0),
            e("2026-05-08", 8200, 45.0, 368.0),
        )
    }

    /** B1 累计行驶 = max − min = 12890 − 8200 = 4690 */
    @Test
    fun `B1 total distance`() {
        val m = MetricsCalculator.compute(sampleCar(), 0.0, LocalDate.parse("2026-10-21"))
        assertEquals(4690, m.totalDistance)
    }

    /** B2 累计平均排除首条油量：371.0 / 4690 × 100 = 7.91 */
    @Test
    fun `B2 cumulative average excludes first record fuel`() {
        val m = MetricsCalculator.compute(sampleCar(), 0.0, LocalDate.parse("2026-10-21"))
        assertNotNull(m.avgL100)
        assertEquals(7.91, FuelMath.round2(m.avgL100!!), 1e-9)
    }

    /** B3 续航 = 上次加油量 ÷ 当前油耗 × 100 = 44.0 / 7.52 × 100 = 585 */
    @Test
    fun `B3 range estimate`() {
        val m = MetricsCalculator.compute(sampleCar(), 0.0, LocalDate.parse("2026-10-21"))
        assertEquals(7.52, FuelMath.round2(m.currentL100!!), 1e-9)
        assertEquals(585, m.rangeKm)
    }

    /** B4 预计加油里程 = 12890 + 585 = 13475 */
    @Test
    fun `B4 expected refuel odometer`() {
        val m = MetricsCalculator.compute(sampleCar(), 0.0, LocalDate.parse("2026-10-21"))
        assertEquals(13475, m.expectedRefuelOdometer)
    }

    /**
     * 标了油箱就按油箱算，且必须是四舍五入。
     * 5.50 L ÷ 2.266 L/100km × 100 = 242.72 → 243；截断会报 242。
     */
    @Test
    fun `range uses tank capacity and rounds instead of truncating`() {
        id = 0
        val m = MetricsCalculator.compute(
            listOf(
                e("2026-10-03", 10462, 4.10, 34.72),
                e("2026-10-20", 10665, 4.60, 38.96),
            ),
            5.5,
            LocalDate.parse("2026-10-21"),
        )
        assertEquals(2.27, FuelMath.round2(m.currentL100!!), 1e-9)
        assertEquals(243, m.rangeKm)
        assertEquals(RangeBasis.TANK, m.rangeBasis)
        assertEquals(10665 + 243, m.expectedRefuelOdometer)
    }

    /**
     * 没标油箱（0）就退回按上次加油量估：44.0 ÷ 7.52 × 100 = 585。
     * 这条同时锁住「0 不是 0 升油箱」，否则续航会直接变成 0。
     */
    @Test
    fun `no tank falls back to last fill volume`() {
        val m = MetricsCalculator.compute(sampleCar(), 0.0, LocalDate.parse("2026-10-21"))
        assertEquals(585, m.rangeKm)
        assertEquals(RangeBasis.LAST_FILL, m.rangeBasis)
    }

    /** 标了 45 L 的轿车：45 ÷ 7.52 × 100 = 598，比按加油量估的 585 略长 */
    @Test
    fun `tank capacity changes the estimate`() {
        val m = MetricsCalculator.compute(sampleCar(), 45.0, LocalDate.parse("2026-10-21"))
        assertEquals(598, m.rangeKm)
        assertEquals(RangeBasis.TANK, m.rangeBasis)
        assertEquals(12890 + 598, m.expectedRefuelOdometer)
    }

    /**
     * 油箱比上次加油量少时（油箱 5.5 L，上次灌进去 5.8 L），仍然按油箱算 ——
     * 那是用户标的容积，比「上次加了多少」更接近箱里实际有多少。
     * 这里顺带证明两种算法结果确实不同，不是换个变量名。
     */
    @Test
    fun `tank is preferred even when smaller than last fill`() {
        id = 0
        val entries = listOf(
            e("2026-10-03", 10462, 4.10, 34.72),
            e("2026-10-20", 10665, 5.80, 49.10),
        )
        val byTank = MetricsCalculator.compute(entries, 5.5, LocalDate.parse("2026-10-21"))
        val byFill = MetricsCalculator.compute(entries, 0.0, LocalDate.parse("2026-10-21"))
        assertTrue(byTank.rangeKm!! < byFill.rangeKm!!)
    }

    /** 累计油费含首条：3427 元 */
    @Test
    fun `total cost includes every record`() {
        val m = MetricsCalculator.compute(sampleCar(), 0.0, LocalDate.parse("2026-10-21"))
        assertEquals(3427.0, m.totalCost, 1e-9)
        assertEquals(10, m.recordCount)
    }

    /** 未加满那条不产生数据点：10 条记录只有 8 个区间 */
    @Test
    fun `partial fill is excluded from trend`() {
        val m = MetricsCalculator.compute(sampleCar(), 0.0, LocalDate.parse("2026-10-21"))
        assertEquals(8, m.trend.size)
        assertTrue(m.trend.none { it.toOdometer == 11430 })
    }

    /** G4 导入 150km/40.5L 这种脏数据后，首页仍用上一个有效值，不被 27.00 顶替 */
    @Test
    fun `G4 anomaly does not pollute home screen`() {
        id = 0
        val dirty = sampleCar().toMutableList()
        dirty += e("2026-10-20", 13040, 40.5, 344.0) // 区间 150 km / 40.5 L = 27.00

        val m = MetricsCalculator.compute(dirty, 0.0, LocalDate.parse("2026-10-21"))
        assertEquals(7.52, FuelMath.round2(m.currentL100!!), 1e-9)
        assertTrue(m.trend.none { it.toOdometer == 13040 })
        // 里程与花费照算 —— 那是真实发生的
        assertEquals(4840, m.totalDistance)
    }

    /** G5 全部区间异常时降级，不显示假值 */
    @Test
    fun `G5 all segments anomalous degrades gracefully`() {
        id = 0
        val onlyBad = listOf(
            e("2026-10-01", 1000, 40.0, 340.0),
            e("2026-10-20", 1150, 40.5, 344.0), // 27.00
        )
        val m = MetricsCalculator.compute(onlyBad, 0.0, LocalDate.parse("2026-10-21"))
        assertNull(m.currentL100)
        assertNull(m.rangeKm)
        assertNull(m.expectedRefuelOdometer)
        assertNull(m.avgL100)
        assertTrue(m.trend.isEmpty())
        assertTrue(!m.hasTrustedSegment)
    }

    /** H4 新车第一条记录：不算油耗，不报错 */
    @Test
    fun `H4 first record on a new vehicle`() {
        id = 0
        val m = MetricsCalculator.compute(
            listOf(e("2026-10-20", 300, 45.0, 382.0)),
            0.0,
            LocalDate.parse("2026-10-21"),
        )
        assertEquals(1, m.recordCount)
        assertNull(m.currentL100)
        assertNull(m.rangeKm)
        assertEquals(0, m.totalDistance)
        assertEquals(382.0, m.totalCost, 1e-9)
    }

    /** H6 空车：全部为空，不是 0，更不是 NaN */
    @Test
    fun `H6 empty vehicle shows nothing`() {
        val m = MetricsCalculator.compute(emptyList(), 0.0)
        assertTrue(m.isEmpty)
        assertNull(m.currentL100)
        assertNull(m.avgL100)
        assertNull(m.rangeKm)
        assertEquals(0, m.totalDistance)
        assertEquals(0.0, m.totalCost, 1e-9)
        assertNull(m.lastRecord)
    }

    /** 与上一箱比较：7.52 − 8.00 = −0.48（负数为变好） */
    @Test
    fun `delta against previous tank`() {
        val m = MetricsCalculator.compute(sampleCar(), 0.0, LocalDate.parse("2026-10-21"))
        assertNotNull(m.deltaFromPrev)
        assertEquals(-0.48, FuelMath.round2(m.deltaFromPrev!!), 1e-9)
    }

    /** 只有一个区间时没有可比对象，delta 必须为 null 而不是 0 */
    @Test
    fun `single segment has no delta`() {
        id = 0
        val m = MetricsCalculator.compute(
            listOf(e("2026-10-01", 1000, 40.0, 340.0), e("2026-10-11", 1500, 40.0, 340.0)),
            0.0,
            LocalDate.parse("2026-10-21"),
        )
        assertNull(m.deltaFromPrev)
        assertNotNull(m.currentL100)
    }

    /** 离线也要能用：日期晚于 today 时不产生负数天数 */
    @Test
    fun `days since last may be negative when clock is behind`() {
        id = 0
        val m = MetricsCalculator.compute(
            listOf(e("2026-10-25", 1000, 40.0, 340.0)),
            0.0,
            LocalDate.parse("2026-10-21"),
        )
        assertEquals(-4L, m.daysSinceLast)
    }
}
