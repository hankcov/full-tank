package com.carassistant.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.carassistant.domain.FuelEntry
import java.time.LocalDate

/**
 * 一次加油。
 *
 * **存绝对里程，不存单次里程。** 单次里程是派生量，某次录错后整条链全错且无法定位；
 * 绝对里程可以独立校验（必须单调递增），错了能指到具体哪一行。
 */
@Entity(
    tableName = "fuel_record",
    foreignKeys = [
        ForeignKey(
            entity = Vehicle::class,
            parentColumns = ["id"],
            childColumns = ["vehicleId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    // 删车连带删记录；复合索引给「按车取里程序」这条最热的查询用
    indices = [Index("vehicleId"), Index(value = ["vehicleId", "odometer"])],
)
data class FuelRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 必填：一条记录必须属于某辆车 */
    val vehicleId: Long,
    val date: LocalDate,
    /** 绝对里程 km */
    val odometer: Int,
    val liters: Double,
    /** 实付金额，含优惠与抹零，不做修正 —— 用户要看的是真花的钱 */
    val amount: Double,
    /** 实付单价 元/L */
    val price: Double,
    /** 是否加满。不加满的记录只累加油量，自身不产生油耗点 */
    val isFull: Boolean = true,
    val station: String? = null,
    val note: String? = null,
    /** 升数由默认油价反推，非用户实填。历史里显示「估」 */
    val estimated: Boolean = false,
)

fun FuelRecord.toEntry(): FuelEntry = FuelEntry(
    id = id,
    date = date,
    odometer = odometer,
    liters = liters,
    amount = amount,
    isFull = isFull,
)
