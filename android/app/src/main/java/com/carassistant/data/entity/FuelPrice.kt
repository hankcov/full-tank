package com.carassistant.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * 省级最高零售价缓存。按标号存一行。
 *
 * 定位：**只用来预填**。加油机上的价格才是准的（有优惠、有抹零），
 * 所以用户手填的值永远优先，任何同步都不能覆盖手填值（判据 C2）。
 */
@Entity(tableName = "fuel_price")
data class FuelPrice(
    @PrimaryKey val grade: String, // "92" / "95" / "98"
    val price: Double,
    /** 该价格的执行日（调价日），不是同步日 */
    val priceDay: LocalDate,
    /** 上次成功同步的时间戳。UI 必须显示，让用户知道这个数有多旧 */
    val syncedAt: Long,
    /** api / manual / fallback */
    val source: String = "manual",
)
