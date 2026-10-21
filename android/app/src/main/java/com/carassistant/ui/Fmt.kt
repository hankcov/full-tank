package com.carassistant.ui

import java.text.DecimalFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val intFmt = DecimalFormat("#,##0")
private val oneFmt = DecimalFormat("0.0")
private val twoFmt = DecimalFormat("0.00")
/** 最多两位小数、不留尾零：45.0 → "45"，5.50 → "5.5"。油箱、标号这类手填值用它 */
private val trimFmt = DecimalFormat("0.##")

object Fmt {
    fun km(v: Int): String = intFmt.format(v)
    fun km(v: Long): String = intFmt.format(v)

    fun yuan(v: Double): String = if (v >= 1000) intFmt.format(v) else twoFmt.format(v)

    /** 油耗固定两位：7.5 和 7.52 差 0.02，对判断趋势没影响，但数字位数跳动会让人以为看错了 */
    fun l100(v: Double): String = twoFmt.format(v)

    fun liters(v: Double): String = oneFmt.format(v)

    fun price(v: Double): String = twoFmt.format(v)

    fun trim(v: Double): String = trimFmt.format(v)

    /** 里程差：90 天前这种 */
    fun ago(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val d = ChronoUnit.DAYS.between(date, today)
        return when {
            d == 0L -> "今天"
            d == 1L -> "昨天"
            d < 0L -> "日期在未来"
            d < 30 -> "$d 天前"
            d < 365 -> "${d / 30} 个月前"
            else -> "${d / 365} 年前"
        }
    }

    fun monthDay(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

    fun sign(v: Double): String = if (v > 0) "↑" else "↓"
}
