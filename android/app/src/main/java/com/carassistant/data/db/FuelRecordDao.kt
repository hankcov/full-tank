package com.carassistant.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.carassistant.data.entity.FuelRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface FuelRecordDao {

    /** 单辆车的全部记录，按时间升序 —— 算法层要的就是这个顺序 */
    @Query("SELECT * FROM fuel_record WHERE vehicleId = :vehicleId ORDER BY date ASC, id ASC")
    suspend fun getByVehicle(vehicleId: Long): List<FuelRecord>

    @Query("SELECT * FROM fuel_record WHERE vehicleId = :vehicleId ORDER BY date ASC, id ASC")
    fun observeByVehicle(vehicleId: Long): Flow<List<FuelRecord>>

    @Query("SELECT * FROM fuel_record ORDER BY vehicleId ASC, date ASC, id ASC")
    suspend fun getAll(): List<FuelRecord>

    @Query("SELECT * FROM fuel_record WHERE id = :id")
    suspend fun get(id: Long): FuelRecord?

    @Query("SELECT MAX(odometer) FROM fuel_record WHERE vehicleId = :vehicleId")
    suspend fun maxOdometer(vehicleId: Long): Int?

    @Query("SELECT COUNT(*) FROM fuel_record WHERE vehicleId = :vehicleId")
    suspend fun countByVehicle(vehicleId: Long): Int

    /** 去重键：同车 + 同日期 + 同里程。两辆车同一天同一里程不冲突 */
    @Query("SELECT COUNT(*) FROM fuel_record WHERE vehicleId = :vehicleId AND date = :date AND odometer = :odometer")
    suspend fun countDuplicate(vehicleId: Long, date: java.time.LocalDate, odometer: Int): Int

    /** 加油站去重后的使用次数，按次数降序（E1）。没加过的站不会出现在结果里（E2） */
    @Query(
        """
        SELECT station AS name, COUNT(*) AS times, MAX(date) AS lastUsed
        FROM fuel_record
        WHERE vehicleId = :vehicleId AND station IS NOT NULL AND station != ''
        GROUP BY station
        ORDER BY times DESC, lastUsed DESC
        """
    )
    suspend fun stationUsage(vehicleId: Long): List<StationUsage>

    @Insert
    suspend fun insert(r: FuelRecord): Long

    @Insert
    suspend fun insertAll(rs: List<FuelRecord>): List<Long>

    @Update
    suspend fun update(r: FuelRecord)

    @Delete
    suspend fun delete(r: FuelRecord)

    @Query("DELETE FROM fuel_record WHERE id = :id")
    suspend fun deleteById(id: Long)
}

data class StationUsage(
    val name: String,
    val times: Int,
    val lastUsed: java.time.LocalDate,
)
