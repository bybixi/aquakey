package dev.waterctl.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WaterRecordDao {

    @Query("SELECT * FROM water_records ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<WaterRecord>>

    @Query("SELECT * FROM water_records ORDER BY startedAt DESC LIMIT 1")
    suspend fun latest(): WaterRecord?

    @Insert
    suspend fun insert(record: WaterRecord): Long

    @Query("DELETE FROM water_records")
    suspend fun clearAll()
}
