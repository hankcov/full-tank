package com.carassistant.domain

import java.time.LocalDate

/**
 * 计算用的加油记录。刻意与 Room 实体分开：
 * 算法层不碰 Android，单测可以在纯 JVM 上毫秒级跑完。
 */
data class FuelEntry(
    val id: Long,
    val date: LocalDate,
    val odometer: Int,
    val liters: Double,
    val amount: Double,
    val isFull: Boolean,
)

/** 区间为何不可信。两种都不是算错，是数据本身有问题。 */
enum class SegmentAnomaly {
    /** 里程没前进（回退或重复），距离 ≤ 0 */
    ODOMETER,

    /** 距离正常，但推算出 3~25 L/100km 之外的油耗 —— 大概率录错了升数或里程 */
    OUT_OF_RANGE,
}

/**
 * 一段可信里程：从上一个加满点到这个加满点。
 *
 * 区间归属「终点记录」—— 油耗是开完这段路才知道的，挂在终点上才符合直觉：
 * 用户在历史里看到某一条，说的是「加这一次油时，上一段跑了多少油」。
 */
data class Segment(
    val fromId: Long,
    val toId: Long,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val fromOdometer: Int,
    val toOdometer: Int,
    val distance: Int,
    val fuel: Double,
    /** 异常区间为 null，绝不返回 0 或 NaN 让 UI 误显示 */
    val l100: Double?,
    val anomaly: SegmentAnomaly?,
) {
    /** 是否可参与首页 / 趋势 / 归因 / 平均 */
    val isTrusted: Boolean get() = anomaly == null && l100 != null
}
