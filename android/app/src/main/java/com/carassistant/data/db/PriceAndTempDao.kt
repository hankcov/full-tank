package com.carassistant.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.carassistant.data.entity.DailyTemp
import com.carassistant.data.entity.FuelPrice
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface FuelPriceDao {

    @Query("SELECT * FROM fuel_price")
    fun observeAll(): Flow<List<FuelPrice>>

    @Query("SELECT * FROM fuel_price")
    suspend fun getAll(): List<FuelPrice>

    @Query("SELECT * FROM fuel_price WHERE grade = :grade")
    suspend fun get(grade: String): FuelPrice?

    @Upsert
    suspend fun upsert(p: FuelPrice)

    @Upsert
    suspend fun upsertAll(ps: List<FuelPrice>)
}

@Dao
interface DailyTempDao {

    @Query("SELECT * FROM daily_temp WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    suspend fun getBetween(from: LocalDate, to: LocalDate): List<DailyTemp>

    @Query("SELECT COUNT(*) FROM daily_temp")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(ts: List<DailyTemp>)
}
