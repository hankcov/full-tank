package com.carassistant.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.carassistant.data.entity.Vehicle
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {

    @Query("SELECT * FROM vehicle ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicle ORDER BY createdAt ASC, id ASC")
    suspend fun getAll(): List<Vehicle>

    @Query("SELECT * FROM vehicle WHERE id = :id")
    suspend fun get(id: Long): Vehicle?

    @Query("SELECT * FROM vehicle WHERE plate = :plate AND plate != '' LIMIT 1")
    suspend fun findByPlate(plate: String): Vehicle?

    @Query("SELECT COUNT(*) FROM vehicle")
    suspend fun count(): Int

    @Insert
    suspend fun insert(v: Vehicle): Long

    @Update
    suspend fun update(v: Vehicle)

    @Delete
    suspend fun delete(v: Vehicle)

    @Query("DELETE FROM vehicle WHERE id = :id")
    suspend fun deleteById(id: Long)
}
