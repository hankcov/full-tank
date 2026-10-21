package com.carassistant.data.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 对应 PLAN.md 判据 G1、G3、G6 —— 导出必须能原样回读，否则「导出兜底」是句空话。
 */
class CsvCodecTest {

    private fun row(
        plate: String = "陕A·8K2L7",
        name: String = "我的车",
        date: String = "2026-10-18",
        odo: Int = 12890,
        liters: Double = 44.0,
        amount: Double = 374.0,
        price: Double = 8.49,
        full: Boolean = true,
        station: String? = "中石化 · 科技路",
        estimated: Boolean = false,
        tank: Double = 0.0,
    ) = CsvRow(
        vehiclePlate = plate,
        vehicleName = name,
        vehicleTank = tank,
        date = LocalDate.parse(date),
        odometer = odo,
        liters = liters,
        amount = amount,
        price = price,
        isFull = full,
        station = station,
        estimated = estimated,
    )

    /** G1 导出→导入回环，每个字段都要一致（含油箱容积：带不过去续航就退回旧算法） */
    @Test
    fun `G1 round trip preserves every field`() {
        val src = listOf(
            row(tank = 45.0),
            row(plate = "陕A·F5D0", name = "家里的SUV", date = "2026-10-11", odo = 44380, liters = 57.5, amount = 515.78, price = 8.97, tank = 60.0),
            row(date = "2026-10-02", odo = 12305, tank = 45.0),
        )
        val back = CsvCodec.parse(CsvCodec.export(src))

        assertTrue(back.ok)
        assertEquals(0, back.skipped)
        assertEquals(3, back.rows.size)
        assertEquals(src, back.rows)
    }

    /** G3 站名带逗号、带双引号，导出再导入原样恢复 */
    @Test
    fun `G3 quotes and commas survive round trip`() {
        val src = listOf(
            row(station = "某站, 带逗号"),
            row(odo = 13000, station = "某站\"引号\""),
            row(odo = 13100, station = "换行\n站名"),
            row(odo = 13200, station = null),
        )
        val csv = CsvCodec.export(src)

        // 有逗号的行必须整体加引号，否则列会错位
        assertTrue(csv.contains("\"某站, 带逗号\""))
        // 内嵌双引号要翻倍
        assertTrue(csv.contains("\"某站\"\"引号\"\"\""))

        val back = CsvCodec.parse(csv)
        assertEquals(4, back.rows.size)
        assertEquals("某站, 带逗号", back.rows[0].station)
        assertEquals("某站\"引号\"", back.rows[1].station)
        assertEquals("换行\n站名", back.rows[2].station)
        assertNull(back.rows[3].station)
    }

    /** 表头缺失时按顺序读不出东西，必须明确报告 unrecognized（G6） */
    @Test
    fun `G6 non csv content is rejected as a whole`() {
        val res = CsvCodec.parse("这不是一个表格\n随便几行字\n没有日期也没有里程")
        assertFalse(res.ok)
        assertTrue(res.unrecognized)
        assertTrue(res.rows.isEmpty())
    }

    /** 空文件同样不可识别，调用方保持数据不动 */
    @Test
    fun `empty file is unrecognized`() {
        assertTrue(CsvCodec.parse("").unrecognized)
        assertTrue(CsvCodec.parse("\r\n\r\n").unrecognized)
    }

    /** 个别坏行只跳过自己，不连累整份文件 */
    @Test
    fun `bad row is skipped but good rows survive`() {
        val csv = """
            vehicle,vehicle_name,date,odometer,liters,amount,price,is_full,station,estimated
            陕A·8K2L7,我的车,2026-10-18,12890,44,374,8.49,1,中石化,0
            陕A·8K2L7,我的车,不是日期,abc,1,1,1,1,,0
            陕A·8K2L7,我的车,2026-10-02,12305,46.8,397,8.49,1,,0
        """.trimIndent()
        val res = CsvCodec.parse(csv)
        assertTrue(res.ok)
        assertEquals(2, res.rows.size)
        assertEquals(1, res.skipped)
        assertEquals(LocalDate.parse("2026-10-02"), res.rows[1].date)
    }

    /** 兼容旧表头（没有 vehicle 列，只有车名）—— 导入时按车名归车 */
    @Test
    fun `legacy header without vehicle column`() {
        val csv = """
            date,odometer,liters,amount,price
            2026-10-18,12890,44,374,8.49
            2026-10-02,12305,46.8,397,8.49
        """.trimIndent()
        val res = CsvCodec.parse(csv)
        assertTrue(res.ok)
        assertEquals(2, res.rows.size)
        assertEquals("", res.rows[0].vehiclePlate)
        assertTrue(res.rows[0].isFull) // 缺 is_full 列默认按加满处理
        assertEquals(0.0, res.rows[0].vehicleTank, 1e-9) // 老文件没有油箱列，按未标注处理
    }

    /** 中文表头里的「油箱」也要认出来 */
    @Test
    fun `chinese tank column is recognized`() {
        val csv = """
            车牌,车名,日期,里程,升数,金额,单价,加满,加油站,油箱
            陕A·8K2L7,我的车,2026-10-18,12890,44,374,8.49,是,中石化,45
        """.trimIndent()
        val res = CsvCodec.parse(csv)
        assertTrue(res.ok)
        assertEquals(45.0, res.rows[0].vehicleTank, 1e-9)
    }

    /** 中文表头也要认 */
    @Test
    fun `chinese header is recognized`() {
        val csv = """
            车牌,车名,日期,里程,升数,金额,单价,加满,加油站
            陕A·8K2L7,我的车,2026-10-18,12890,44,374,8.49,是,中石化
        """.trimIndent()
        val res = CsvCodec.parse(csv)
        assertTrue(res.ok)
        assertEquals(1, res.rows.size)
        assertEquals("陕A·8K2L7", res.rows[0].vehiclePlate)
        assertTrue(res.rows[0].isFull)
    }

    /** 单价缺失时用金额 / 升数反推，不留 0 */
    @Test
    fun `price is derived when column missing`() {
        val csv = """
            date,odometer,liters,amount
            2026-10-18,12890,44,374
        """.trimIndent()
        val row = CsvCodec.parse(csv).rows.single()
        assertEquals(8.5, row.price, 1e-9)
    }

    /** Excel 存的 UTF-8 BOM 不能把第一个列名搞坏 */
    @Test
    fun `bom is stripped`() {
        val csv = "\uFEFF" + CsvCodec.HEADER + "\r\n陕A·8K2L7,我的车,2026-10-18,12890,44,374,8.49,1,,0\r\n"
        val res = CsvCodec.parse(csv)
        assertTrue(res.ok)
        assertEquals(1, res.rows.size)
    }

    /** 导出用 CRLF，Excel 打开不乱行 */
    @Test
    fun `export uses crlf and fixed header`() {
        val csv = CsvCodec.export(listOf(row()))
        assertTrue(csv.startsWith(CsvCodec.HEADER))
        assertTrue(csv.contains("\r\n"))
    }

    /** 整数不带 .0，避免 Excel 里显示成 44.0 */
    @Test
    fun `integral numbers omit decimal point`() {
        val csv = CsvCodec.export(listOf(row(liters = 44.0, amount = 374.0)))
        val line = csv.lines()[1]
        assertTrue(line.contains(",44,"))
        assertTrue(line.contains(",374,"))
    }
}
