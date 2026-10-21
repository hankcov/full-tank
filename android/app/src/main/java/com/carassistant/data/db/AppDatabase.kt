package com.carassistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.carassistant.data.entity.DailyTemp
import com.carassistant.data.entity.FuelPrice
import com.carassistant.data.entity.FuelRecord
import com.carassistant.data.entity.Vehicle
import java.time.LocalDate

class Converters {
    @TypeConverter fun toEpochDay(d: LocalDate?): Long? = d?.toEpochDay()
    @TypeConverter fun fromEpochDay(v: Long?): LocalDate? = v?.let(LocalDate::ofEpochDay)
}

@Database(
    entities = [Vehicle::class, FuelRecord::class, FuelPrice::class, DailyTemp::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun vehicleDao(): VehicleDao
    abstract fun fuelRecordDao(): FuelRecordDao
    abstract fun fuelPriceDao(): FuelPriceDao
    abstract fun dailyTempDao(): DailyTempDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "car_assistant.db")
                // 自用工具，单机无同步；真出问题时靠 CSV 导出兜底，不做迁移脚本
                .fallbackToDestructiveMigration()
                .build()
    }
}
