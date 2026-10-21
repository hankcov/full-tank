package com.carassistant.data.csv

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 一条待导出 / 刚解析出的记录 */
data class CsvRow(
    val vehiclePlate: String,
    val vehicleName: String,
    /** 该车油箱容积（L），0 = 未标注。行级冗余：导出靠它把车的属性一起带走 */
    val vehicleTank: Double,
    val date: LocalDate,
    val odometer: Int,
    val liters: Double,
    val amount: Double,
    val price: Double,
    val isFull: Boolean,
    val station: String?,
    val estimated: Boolean,
)

data class ParseResult(
    val rows: List<CsvRow>,
    /** 无法识别的数据行（列数不对、日期/数字解析失败），不静默丢弃，要计数 */
    val skipped: Int,
    /** 表头一个可识别列都没有 —— 说明选错文件了，调用方应保持数据不动 */
    val unrecognized: Boolean,
) {
    val ok: Boolean get() = !unrecognized
}

/**
 * CSV 导出 / 导入。
 *
 * 自己写而不引库：只需要 RFC4180 的一个子集，引个库反而要处理它的边界行为。
 *
 * 表头固定十列，**vehicle 列存车牌**（导入时靠它认车），**vehicle_name 存车名**。
 * 为什么两个都存：车牌是身份，车名是用户起的，只存车牌导入后名字就退化成「陕A·8K2L7」了（H7）。
 */
object CsvCodec {

    /**
     * tank 放在最后：老版本导出的文件没有这一列，按列名定位缺列照样能读；
     * 新文件给老版本读也只是多一列被忽略。油箱标了才能算满箱续航，
     * 不带上它，导一次就退化成「按上次加油量估」了。
     */
    const val HEADER = "vehicle,vehicle_name,date,odometer,liters,amount,price,is_full,station,estimated,tank"

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ---- 导出 ----

    fun export(rows: List<CsvRow>): String = buildString {
        append(HEADER).append("\r\n")
        for (r in rows) {
            append(quote(r.vehiclePlate)).append(',')
            append(quote(r.vehicleName)).append(',')
            append(r.date.format(DATE_FMT)).append(',')
            append(r.odometer).append(',')
            append(num(r.liters)).append(',')
            append(num(r.amount)).append(',')
            append(num(r.price)).append(',')
            append(if (r.isFull) "1" else "0").append(',')
            append(quote(r.station ?: "")).append(',')
            append(if (r.estimated) "1" else "0").append(',')
            append(num(r.vehicleTank))
            append("\r\n")
        }
    }

    /** 含逗号、引号、换行的字段必须整体加引号，内部引号翻倍（G3） */
    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    /** 去掉无意义的 .0，让文件在 Excel 里看着干净 */
    private fun num(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ---- 导入 ----

    private val PLATE_KEYS = setOf("vehicle", "plate", "车牌", "车牌号")
    private val NAME_KEYS = setOf("vehicle_name", "vehiclename", "name", "车名", "车辆", "车辆名称")
    private val DATE_KEYS = setOf("date", "日期")
    private val ODO_KEYS = setOf("odometer", "odo", "mileage", "里程", "总里程")
    private val LITERS_KEYS = setOf("liters", "litre", "liter", "volume", "升数", "油量")
    private val AMOUNT_KEYS = setOf("amount", "cost", "金额", "油费", "总价")
    private val PRICE_KEYS = setOf("price", "单价", "油价")
    private val FULL_KEYS = setOf("is_full", "isfull", "full", "加满")
    private val STATION_KEYS = setOf("station", "加油站")
    private val ESTIMATED_KEYS = setOf("estimated", "估", "估算")
    private val TANK_KEYS = setOf("tank", "tank_capacity", "tankcapacity", "油箱", "油箱容积")

    fun parse(text: String): ParseResult {
        val all = readRows(text)
        if (all.isEmpty()) return ParseResult(emptyList(), 0, unrecognized = true)

        val header = all.first().map { it.trim().lowercase() }
        // 列定位：缺列就退化成 -1，解析时按缺失处理而不是整表报废
        fun col(keys: Set<String>) = header.indexOfFirst { it in keys }

        val iPlate = col(PLATE_KEYS)
        val iName = col(NAME_KEYS)
        val iDate = col(DATE_KEYS)
        val iOdo = col(ODO_KEYS)
        val iLiters = col(LITERS_KEYS)
        val iAmount = col(AMOUNT_KEYS)
        val iPrice = col(PRICE_KEYS)
        val iFull = col(FULL_KEYS)
        val iStation = col(STATION_KEYS)
        val iEst = col(ESTIMATED_KEYS)
        val iTank = col(TANK_KEYS)

        // 日期 + 里程都认不出来，基本可以断定这不是本 App 导出的文件（G6）
        if (iDate < 0 || iOdo < 0) return ParseResult(emptyList(), 0, unrecognized = true)

        val rows = ArrayList<CsvRow>()
        var skipped = 0
        for (idx in 1 until all.size) {
            val f = all[idx]
            if (f.all { it.isBlank() }) continue

            fun at(i: Int): String = if (i in f.indices) f[i].trim() else ""

            val date = runCatching { LocalDate.parse(at(iDate), DATE_FMT) }.getOrNull()
            val odo = at(iOdo).toDoubleOrNull()?.toInt()
            if (date == null || odo == null) {
                skipped++
                continue
            }

            val liters = at(iLiters).toDoubleOrNull() ?: 0.0
            val amount = at(iAmount).toDoubleOrNull() ?: 0.0
            // 单价缺失时用金额/升数反推，比留 0 有用
            val price = at(iPrice).toDoubleOrNull()
                ?: if (liters > 0) amount / liters else 0.0

            rows += CsvRow(
                vehiclePlate = at(iPlate),
                vehicleName = at(iName),
                vehicleTank = if (iTank < 0) 0.0 else (at(iTank).toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                date = date,
                odometer = odo,
                liters = liters,
                amount = amount,
                price = price,
                isFull = if (iFull < 0) true else at(iFull).toBool(default = true),
                station = at(iStation).ifBlank { null },
                estimated = if (iEst < 0) false else at(iEst).toBool(default = false),
            )
        }
        return ParseResult(rows, skipped, unrecognized = false)
    }

    private fun String.toBool(default: Boolean): Boolean = when (lowercase()) {
        "1", "true", "yes", "y", "是", "加满" -> true
        "0", "false", "no", "n", "否", "未满", "未加满" -> false
        else -> default
    }

    /**
     * RFC4180 子集：引号内允许逗号、换行、双写转义的双引号。
     * 手写状态机而不是 split(',')，否则站名里带个逗号整行就错位了（G3）。
     */
    private fun readRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        val s = text.removePrefix("\uFEFF") // 去掉 Excel 存的 BOM，否则第一个列名匹配不上

        fun endCell() { row.add(cell.toString()); cell.clear() }
        fun endRow() {
            endCell()
            if (row.any { it.isNotBlank() }) rows.add(row)
            row = ArrayList()
        }

        while (i < s.length) {
            val c = s[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { cell.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> cell.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> endCell()
                c == '\r' -> { if (i + 1 < s.length && s[i + 1] == '\n') i++ ; endRow() }
                c == '\n' -> endRow()
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
