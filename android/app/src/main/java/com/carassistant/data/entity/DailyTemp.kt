package com.carassistant.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * 日平均气温。拉到就永久留着 —— 历史气温不会变，
 * 存下来才能断网也做归因。
 */
@Entity(tableName = "daily_temp")
data class DailyTemp(
    @PrimaryKey val date: LocalDate,
    /** ℃ */
    val tempAvg: Double,
    val fetchedAt: Long,
)
