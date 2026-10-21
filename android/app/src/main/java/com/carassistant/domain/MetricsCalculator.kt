package com.carassistant.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 首页指标。全部由记录现算，不落库 —— 派生量一旦存下来，
 * 删改一条记录就要同步修一堆缓存，出错就是静默的。
 */
/** 续航是按什么估的。界面上要说清楚，否则用户没法判断这个数可信不可信 */
enum class RangeBasis {
    /** 按车标注的油箱容积：满箱能跑多远 */
    TANK,

    /** 车没标油箱，退回按上次加油量估：比实际偏小（少了箱底余油） */
    LAST_FILL,
}

data class VehicleMetrics(
    val recordCount: Int,
    /** 最近一个可信区间的油耗；没有可信区间为 null */
    val currentL100: Double?,
    /** 与上一箱比较，正数为变差（多烧油）。只有两次以上可信区间才有值 */
    val deltaFromPrev: Double?,
    val totalDistance: Int,
    val totalCost: Double,
    /** 按可信区间累计，自动剔除异常数据 */
    val avgL100: Double?,
    /** 一箱油能跑多远 */
    val rangeKm: Int?,
    /** 续航是按油箱容积还是按上次加油量估的；rangeKm 为 null 时无意义 */
    val rangeBasis: RangeBasis,
    /** 这一箱跑完时的里程表读数 */
    val expectedRefuelOdometer: Int?,
    val lastRecord: FuelEntry?,
    val daysSinceLast: Long?,
    /** 趋势数据点，按日期升序 */
    val trend: List<Segment>,
) {
    val isEmpty: Boolean get() = recordCount == 0
    val hasTrustedSegment: Boolean get() = currentL100 != null

    companion object {
        val EMPTY = VehicleMetrics(
            recordCount = 0, currentL100 = null, deltaFromPrev = null,
            totalDistance = 0, totalCost = 0.0, avgL100 = null,
            rangeKm = null, rangeBasis = RangeBasis.LAST_FILL, expectedRefuelOdometer = null,
            lastRecord = null, daysSinceLast = null, trend = emptyList(),
        )
    }
}

object MetricsCalculator {

    /**
     * @param entries 已经按 vehicleId 过滤过的单辆车记录。
     *                **不要传全库记录** —— 会串车，见 FuelMath 注释。
     * @param tankCapacity 该车油箱容积（L）。续航按它算，填错则结论不可用，
     *                     所以首页要把它显示出来，让错的数字自己露出来。
     */
    fun compute(
        entries: List<FuelEntry>,
        tankCapacity: Double,
        today: LocalDate = LocalDate.now(),
    ): VehicleMetrics {
        if (entries.isEmpty()) return VehicleMetrics.EMPTY

        val trusted = FuelMath.segments(entries).filter { it.isTrusted }
        // 最新在前：先按终点日期，再按终点里程，同日多条用 id 兜底
        val latestFirst = trusted.sortedWith(
            compareByDescending<Segment> { it.toDate }
                .thenByDescending { it.toOdometer }
                .thenByDescending { it.toId }
        )

        val current = latestFirst.firstOrNull()
        val prev = latestFirst.getOrNull(1)

        val maxOdo = entries.maxOf { it.odometer }
        val minOdo = entries.minOf { it.odometer }
        val lastRecord = entries.maxWithOrNull(
            compareBy<FuelEntry> { it.date }.thenBy { it.odometer }.thenBy { it.id }
        )

        val totalFuel = trusted.sumOf { it.fuel }
        val totalDist = trusted.sumOf { it.distance }
        val avg = if (totalDist > 0) totalFuel / totalDist * 100 else null

        // 续航两条路，按「车有没有标油箱」选：
        //
        //   标了  → 油箱容积 ÷ 当前油耗 × 100，即满箱能跑多远
        //   没标  → 上次加油量 ÷ 当前油耗 × 100，缺了箱底余油，偏低
        //
        // 为什么标了就优先用油箱：最后加了多少是「那次加了多少」，不是「箱里有多少」。
        // 加满时两者接近，没加满时后者会明显低估。前提是容积填对 ——
        // 摩托车和轿车差一个数量级，所以首页把用到的数一起显示出来。
        //
        // 取整用 round 不用截断：5.50/(4.60/203*100)*100 数学上 242.72，
        // 浮点可能算出 242.71999…，截断会少报 1 公里。
        val hasTank = tankCapacity > 0
        val basis = if (hasTank) RangeBasis.TANK else RangeBasis.LAST_FILL
        val rangeFuel = if (hasTank) tankCapacity else lastRecord?.liters?.takeIf { it > 0 }
        val curL100 = current?.l100
        val range = if (curL100 != null && curL100 > 0 && rangeFuel != null) {
            (rangeFuel / curL100 * 100).roundToInt()
        } else {
            null
        }

        // 起点不一样：按油箱算时从「上次加满那一刻」起算（满箱是那时注满的），
        // 不是最后一条记录的里程 —— 中间可能夹了一次没加满的补油。
        // 按上次加油量算时，那箱油就是最后一条记录时加进去的，起点自然是它。
        val rangeStart = if (hasTank) current?.toOdometer ?: maxOdo else maxOdo

        return VehicleMetrics(
            recordCount = entries.size,
            currentL100 = current?.l100,
            deltaFromPrev = if (current?.l100 != null && prev?.l100 != null) {
                current.l100 - prev.l100
            } else {
                null
            },
            totalDistance = maxOdo - minOdo,
            totalCost = entries.sumOf { it.amount },
            avgL100 = avg,
            rangeKm = range,
            rangeBasis = basis,
            expectedRefuelOdometer = range?.let { rangeStart + it },
            lastRecord = lastRecord,
            daysSinceLast = lastRecord?.let { ChronoUnit.DAYS.between(it.date, today) },
            trend = trusted.sortedWith(
                compareBy<Segment> { it.toDate }.thenBy { it.toOdometer }
            ),
        )
    }
}
