package com.carassistant.domain

/**
 * 满箱法。
 *
 * 不用「相邻两条记录相除」—— 那样一遇到没加满就得出垃圾值。
 * 正确做法是按**加满事件切分区间**，中间未加满的油量累加进区间，自身不出油耗点。
 *
 * 三条铁律（都是踩过的坑）：
 * 1. 必须先按 vehicleId 分组再调用。两辆车里程表读数通常高度重叠（都在 1~5 万），
 *    混在一起会让加满点跨车配对，算出一个「看起来像个数」的荒谬值。
 * 2. 时间线排序（date, id），不是按 odometer 排。
 *    按里程排会把「里程填错的那条」静默挪到别处重新配对，错误被藏起来 —— 看着能跑，其实串了区间。
 *    按时间线排 + 显式单调性校验，才能把填错的那条揪出来标异常。
 * 3. 算不出来就返回 null，不返回 0。0 会在 UI 上显示成「油耗 0.00」，比空着更误导。
 */
object FuelMath {

    /**
     * 物理合理区间。
     *
     * 下限取 1.0 而不是 3.0：3.0 是轿车经验值，会把这辆摩托车（1.76~3.18）整表判成异常。
     * 1.0 能覆盖摩托车、踏板、混动，同时仍拦得住「漏记一次加油」导致的天文低值（0.5 以下）。
     * 上限 25 管的是里程或升数录错（比如 150 km 记成 1500 km）。
     */
    const val MIN_PLAUSIBLE_L100 = 1.0
    const val MAX_PLAUSIBLE_L100 = 25.0

    /**
     * 按加满点切分区间，结果挂在**终点记录**上。
     * 首条记录作为基准点，不产生区间（A1）。
     */
    fun segments(entries: List<FuelEntry>): List<Segment> {
        if (entries.size < 2) return emptyList()

        val timeline = entries.sortedWith(compareBy({ it.date }, { it.id }))

        // 里程必须严格递增。回退或重复的那条不进配对，否则负距离会污染整条链
        val rejected = BooleanArray(timeline.size)
        var maxOdo = Int.MIN_VALUE
        for (i in timeline.indices) {
            val odo = timeline[i].odometer
            if (odo <= maxOdo) rejected[i] = true else maxOdo = odo
        }

        val result = ArrayList<Segment>()
        var anchor = -1 // 上一个**可信**加满点的时间线下标

        for (i in timeline.indices) {
            val b = timeline[i]
            if (!b.isFull) continue

            if (rejected[i]) {
                // 里程异常：有锚点就出一条异常区间，让 UI 能指出来是哪一行
                if (anchor >= 0) {
                    val a = timeline[anchor]
                    result += Segment(
                        fromId = a.id, toId = b.id,
                        fromDate = a.date, toDate = b.date,
                        fromOdometer = a.odometer, toOdometer = b.odometer,
                        distance = b.odometer - a.odometer,
                        fuel = sumLiters(timeline, anchor, i),
                        l100 = null, anomaly = SegmentAnomaly.ODOMETER,
                    )
                }
                // 不推进锚点：它的里程不可信，后面的区间不能从它起算
                continue
            }

            if (anchor < 0) {
                anchor = i
                continue
            }

            val a = timeline[anchor]
            val distance = b.odometer - a.odometer
            val fuel = sumLiters(timeline, anchor, i)

            if (distance <= 0) {
                // 单调性校验后理论上到不了这里，留着兜底，绝不返回 NaN / Infinity
                result += Segment(
                    fromId = a.id, toId = b.id,
                    fromDate = a.date, toDate = b.date,
                    fromOdometer = a.odometer, toOdometer = b.odometer,
                    distance = distance, fuel = fuel,
                    l100 = null, anomaly = SegmentAnomaly.ODOMETER,
                )
                anchor = i
                continue
            }

            val l100 = fuel / distance * 100
            val anomaly = if (l100 < MIN_PLAUSIBLE_L100 || l100 > MAX_PLAUSIBLE_L100) {
                SegmentAnomaly.OUT_OF_RANGE
            } else {
                null
            }
            result += Segment(
                fromId = a.id, toId = b.id,
                fromDate = a.date, toDate = b.date,
                fromOdometer = a.odometer, toOdometer = b.odometer,
                distance = distance, fuel = fuel,
                l100 = l100, anomaly = anomaly,
            )
            anchor = i
        }
        return result
    }

    /** 含 to、不含 from：from 加的那箱油是上一段烧掉的 */
    private fun sumLiters(timeline: List<FuelEntry>, fromIdx: Int, toIdx: Int): Double {
        var sum = 0.0
        for (k in fromIdx + 1..toIdx) sum += timeline[k].liters
        return sum
    }

    /** 按终点记录 id 索引，供历史列表逐行标注异常用 */
    fun segmentsByEndId(entries: List<FuelEntry>): Map<Long, Segment> =
        segments(entries).associateBy { it.toId }

    /** 去掉浮点尾巴再展示，避免 7.910000000000001 这种 */
    fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
