package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CycleDayLogDao {
    @Query("SELECT * FROM cycle_day_logs ORDER BY dateString DESC")
    fun getAllLogs(): Flow<List<CycleDayLog>>

    @Query("SELECT * FROM cycle_day_logs ORDER BY dateString DESC")
    suspend fun getAllLogsList(): List<CycleDayLog>

    @Query("SELECT * FROM cycle_day_logs WHERE dateString = :date LIMIT 1")
    suspend fun getLogByDate(date: String): CycleDayLog?

    @Query("SELECT * FROM cycle_day_logs WHERE dateString = :date LIMIT 1")
    fun getLogByDateFlow(date: String): Flow<CycleDayLog?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateLog(log: CycleDayLog)

    @Query("DELETE FROM cycle_day_logs WHERE dateString = :date")
    suspend fun deleteLog(date: String)

    @Query("DELETE FROM cycle_day_logs")
    suspend fun deleteAllLogs()

    @Query("SELECT * FROM cycle_settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<CycleSettings?>

    @Query("SELECT * FROM cycle_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): CycleSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: CycleSettings)
}
