package com.carassistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 对应 PLAN.md 判据 A1–A9。每条都是可复算的具体数字，不接受「看起来对」。
 */
class FuelMathTest {

    private var nextId = 0L
    private fun e(
        date: String,
        odo: Int,
        liters: Double,
        isFull: Boolean = true,
        amount: Double = liters * 8.49,
    ) = FuelEntry(
        id = ++nextId,
        date = LocalDate.parse(date),
        odometer = odo,
        liters = liters,
        amount = amount,
        isFull = isFull,
    )

    private fun rounded(seg: Segment) = FuelMath.round2(seg.l100!!)

    /** A1 首条记录只是基准点，不产生油耗值 */
    @Test
    fun `A1 first record produces no segment`() {
        val segs = FuelMath.segments(listOf(e("2026-05-08", 8200, 45.0)))
        assertTrue(segs.isEmpty())
    }

    /** A2 两次连续加满直接算：40 / 500 × 100 = 8.00，用 35 / 500 → 7.00 */
    @Test
    fun `A2 two consecutive full tanks`() {
        val segs = FuelMath.segments(
            listOf(
                e("2026-05-01", 1000, 40.0),
                e("2026-05-20", 1500, 35.0),
            )
        )
        assertEquals(1, segs.size)
        assertEquals(500, segs[0].distance)
        assertEquals(35.0, segs[0].fuel, 1e-9)
        assertEquals(7.00, rounded(segs[0]), 1e-9)
    }

    /** A3 中间未加满：油量累加，不单独成点 → (20+25)/500×100 = 9.00 */
    @Test
    fun `A3 partial fill accumulates into segment`() {
        val segs = FuelMath.segments(
            listOf(
                e("2026-05-01", 1000, 40.0),
                e("2026-05-10", 1300, 20.0, isFull = false),
                e("2026-05-20", 1500, 25.0),
            )
        )
        assertEquals(1, segs.size)
        assertEquals(45.0, segs[0].fuel, 1e-9)
        assertEquals(9.00, rounded(segs[0]), 1e-9)
    }

    /** A4 未加满记录不作为区间终点出现在结果里 */
    @Test
    fun `A4 partial fill never becomes a point`() {
        val partial = e("2026-05-10", 1300, 20.0, isFull = false)
        val segs = FuelMath.segments(
            listOf(
                e("2026-05-01", 1000, 40.0),
                partial,
                e("2026-05-20", 1500, 25.0),
            )
        )
        assertTrue(segs.none { it.toId == partial.id })
    }

    /** A5 里程回退：该条被标异常且不推进锚点，后面的区间不能从它起算 */
    @Test
    fun `A5 odometer rollback is flagged`() {
        val rollback = e("2026-05-25", 1400, 35.0)
        val segs = FuelMath.segments(
            listOf(
                e("2026-05-01", 1000, 40.0),
                e("2026-05-20", 1500, 35.0),
                rollback, // 回退
            )
        )
        assertEquals(2, segs.size)

        // 正常那段照算
        assertEquals(7.00, rounded(segs[0]), 1e-9)

        // 回退那条单独出一个异常区间，UI 才能指到具体哪一行
        val bad = segs[1]
        assertEquals(rollback.id, bad.toId)
        assertEquals(SegmentAnomaly.ODOMETER, bad.anomaly)
        assertNull(bad.l100)
        assertTrue(bad.distance < 0)
        assertTrue(!bad.isTrusted)
    }

    /** A6 里程重复：不除零，不产生 Infinity / NaN */
    @Test
    fun `A6 duplicate odometer never divides by zero`() {
        val segs = FuelMath.segments(
            listOf(
                e("2026-05-01", 1000, 40.0),
                e("2026-05-20", 1500, 35.0),
                e("2026-05-21", 1500, 35.0), // 同里程
            )
        )
        assertEquals(2, segs.size)
        val bad = segs[1]
        assertEquals(0, bad.distance)
        assertNull(bad.l100)
        assertEquals(SegmentAnomaly.ODOMETER, bad.anomaly)
        assertTrue(!bad.isTrusted)
        assertTrue(segs.none { it.l100 != null && (it.l100!!.isNaN() || it.l100!!.isInfinite()) })
    }

    /** A9 精度：结果与手工核算一致到小数点后两位 */
    @Test
    fun `A9 precision matches hand calculation`() {
        val segs = FuelMath.segments(
            listOf(
                e("2026-05-01", 9385, 47.2),
                e("2026-06-16", 9980, 49.5),
            )
        )
        // 49.5 / 595 × 100 = 8.31932...
        assertEquals(8.32, rounded(segs[0]), 1e-9)
    }

    /** 没有加满事件时一条区间都算不出来，且不抛异常 */
    @Test
    fun `no full tank means no segment`() {
        val segs = FuelMath.segments(
            listOf(
                e("2026-05-01", 1000, 20.0, isFull = false),
                e("2026-05-10", 1300, 20.0, isFull = false),
            )
        )
        assertTrue(segs.isEmpty())
    }

    /** OUT_OF_RANGE 也要被隔离：150 km 烧 40.5 L = 27.00 */
    @Test
    fun `out of range consumption is not trusted`() {
        val segs = FuelMath.segments(
            listOf(
                e("2026-05-01", 1000, 40.0),
                e("2026-05-05", 1150, 40.5),
            )
        )
        assertEquals(1, segs.size)
        assertNotNull(segs[0].l100)
        assertEquals(27.00, rounded(segs[0]), 1e-9)
        assertEquals(SegmentAnomaly.OUT_OF_RANGE, segs[0].anomaly)
        assertTrue(!segs[0].isTrusted)
    }

    /** 边界值本身可信：1.00 与 25.00 都在范围内（判据用严格大于小于） */
    @Test
    fun `boundary values are still trusted`() {
        val low = FuelMath.segments(
            listOf(e("2026-05-01", 1000, 40.0), e("2026-05-10", 2000, 10.0))
        )
        assertEquals(1.00, rounded(low[0]), 1e-9)
        assertTrue(low[0].isTrusted)

        val high = FuelMath.segments(
            listOf(e("2026-05-01", 1000, 40.0), e("2026-05-10", 1200, 50.0))
        )
        assertEquals(25.00, rounded(high[0]), 1e-9)
        assertTrue(high[0].isTrusted)
    }

    /** 摩托车的正常油耗（1.8~2.5）不能被当成异常 —— 这是真实数据踩出来的坑 */
    @Test
    fun `motorcycle consumption is trusted`() {
        val segs = FuelMath.segments(
            listOf(
                e("2026-10-03", 8980, 4.10),
                e("2026-10-20", 9165, 4.60),
            )
        )
        // 4.60 / 185 × 100 = 2.486
        assertEquals(2.49, rounded(segs[0]), 1e-9)
        assertTrue(segs[0].isTrusted)
        assertNull(segs[0].anomaly)
    }

    /** 下限 1.00 仍然拦得住「漏记一次加油」造出来的天文低值 */
    @Test
    fun `absurdly low consumption is still rejected`() {
        val segs = FuelMath.segments(
            listOf(e("2026-05-01", 1000, 40.0), e("2026-05-10", 2000, 5.0))
        )
        assertEquals(0.50, rounded(segs[0]), 1e-9)
        assertEquals(SegmentAnomaly.OUT_OF_RANGE, segs[0].anomaly)
    }

    /** 乱序输入不影响结果：排序键是里程 */
    @Test
    fun `input order does not matter`() {
        val a = e("2026-05-01", 1000, 40.0)
        val b = e("2026-05-20", 1500, 35.0)
        val forward = FuelMath.segments(listOf(a, b))
        val backward = FuelMath.segments(listOf(b, a))
        assertEquals(forward[0].distance, backward[0].distance)
        assertEquals(rounded(forward[0]), rounded(backward[0]), 1e-9)
    }
}
