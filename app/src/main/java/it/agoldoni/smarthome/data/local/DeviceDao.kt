package it.agoldoni.smarthome.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {

    @Query("SELECT * FROM devices ORDER BY room COLLATE NOCASE, name COLLATE NOCASE")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices")
    suspend fun findAll(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun findById(id: Long): DeviceEntity?

    @Insert
    suspend fun insert(device: DeviceEntity): Long

    @Update
    suspend fun update(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun delete(id: Long)

    // -- applicazione di un registro ------------------------------------
    //
    // In blocco e non uno per uno: ogni scrittura fa riemettere il Flow di
    // observeAll(), e quel Flow arriva fino a driver.track(). Sette prese
    // applicate una alla volta sarebbero sette giri di risottoscrizione.

    @Insert
    suspend fun insertAll(devices: List<DeviceEntity>)

    @Update
    suspend fun updateAll(devices: List<DeviceEntity>)

    @Query("DELETE FROM devices WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)
}
