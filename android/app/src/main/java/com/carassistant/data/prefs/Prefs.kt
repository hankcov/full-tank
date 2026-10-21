package com.carassistant.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

private val KEY_CURRENT_VEHICLE = longPreferencesKey("current_vehicle_id")

/**
 * 当前车不是业务数据，是一条配置，所以放 DataStore 而不是 Room。
 * 存 id 不存下标 —— 下标会随增删车漂移，删掉中间一辆就指错车了。
 */
class Prefs(private val context: Context) {

    val currentVehicleId: Flow<Long?> =
        context.dataStore.data.map { it[KEY_CURRENT_VEHICLE] }

    suspend fun setCurrentVehicle(id: Long?) {
        context.dataStore.edit { p ->
            if (id == null) p.remove(KEY_CURRENT_VEHICLE) else p[KEY_CURRENT_VEHICLE] = id
        }
    }
}
