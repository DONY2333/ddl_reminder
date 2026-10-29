package com.example.ddlreminder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** DAO（Data Access Object）集中定义数据库允许执行的操作。 */
@Dao
interface DdlDao {
    @Query("SELECT * FROM ddls ORDER BY deadlineMillis ASC")
    fun observeAll(): Flow<List<DdlEntity>>

    @Query("SELECT * FROM ddls WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DdlEntity?

    @Query("SELECT * FROM ddls WHERE isCompleted = 0 AND deadlineMillis > :nowMillis")
    suspend fun getActiveFuture(nowMillis: Long): List<DdlEntity>

    @Query("SELECT * FROM ddls WHERE isCompleted = 0 AND deadlineMillis <= :nowMillis")
    suspend fun getOverdue(nowMillis: Long): List<DdlEntity>

    @Insert
    suspend fun insert(ddl: DdlEntity): Long

    @Update
    suspend fun update(ddl: DdlEntity)

    @Delete
    suspend fun delete(ddl: DdlEntity)
}
