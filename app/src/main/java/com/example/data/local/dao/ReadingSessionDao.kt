package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingSessionDao {
    @Query("SELECT * FROM reading_sessions ORDER BY started_at DESC")
    fun observeAllSessions(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE book_id = :bookId ORDER BY started_at DESC")
    fun observeSessionsForBook(bookId: String): Flow<List<ReadingSessionEntity>>

    @Query("SELECT COALESCE(SUM(duration_ms), 0) FROM reading_sessions")
    fun observeTotalReadingDurationMs(): Flow<Long>

    @Query("SELECT COALESCE(SUM(words_read), 0) FROM reading_sessions")
    fun observeTotalWordsRead(): Flow<Int>

    @Query("SELECT COALESCE(AVG(NULLIF(average_wpm, 0)), 0) FROM reading_sessions")
    fun observeOverallAverageWpm(): Flow<Double>

    @Query("SELECT DISTINCT session_date_epoch_day FROM reading_sessions ORDER BY session_date_epoch_day DESC")
    fun observeActiveReadingEpochDays(): Flow<List<Long>>

    @Query(
        """
        SELECT * FROM reading_sessions 
        WHERE session_date_epoch_day >= :fromEpochDay 
        ORDER BY session_date_epoch_day ASC
        """
    )
    fun observeSessionsSinceEpochDay(fromEpochDay: Long): Flow<List<ReadingSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ReadingSessionEntity)

    @Query("DELETE FROM reading_sessions WHERE book_id = :bookId")
    suspend fun deleteSessionsForBook(bookId: String)
}
