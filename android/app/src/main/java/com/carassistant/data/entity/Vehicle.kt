package com.carassistant.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一辆车。
 *
 * 多车是硬需求（家里两辆车分开记账），但**车与车之间完全隔离**：
 * 任何按里程的计算都必须先按 vehicleId 过滤，见 FuelMath 注释。
 */
@Entity(tableName = "vehicle", indices = [Index(value = ["plate"], unique = false)])
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 选填。导入时靠它认车；空串不参与匹配，避免两辆没填车牌的车互相吞记录 */
    val plate: String = "",
    /**
     * 升。**0 表示没标注**，不是「0 升油箱」。
     *
     * 标了就按它算满箱续航（`tank ÷ 油耗 × 100`）；没标则退回「上次加油量 ÷ 油耗」。
     * 不设默认值：默认填 45 会让摩托车算出 2000 km 的续航，
     * 而那个 45 看起来跟用户自己填的一模一样，没法分辨。
     */
    val tankCapacity: Double = 0.0,
    /** 默认标号，跟着车走。录入时用它带出对应油价 */
    val fuelGrade: String = "92",
    val createdAt: Long = System.currentTimeMillis(),
)
