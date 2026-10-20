package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.TtsSyncCueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TtsSyncCueDao {
    @Query("SELECT * FROM tts_sync_cues WHERE book_id = :bookId AND chapter_index = :chapterIndex ORDER BY sentence_index ASC")
    fun observeCuesForChapter(bookId: String, chapterIndex: Int): Flow<List<TtsSyncCueEntity>>

    @Query("SELECT * FROM tts_sync_cues WHERE book_id = :bookId AND chapter_index = :chapterIndex ORDER BY sentence_index ASC")
    suspend fun getCuesForChapter(bookId: String, chapterIndex: Int): List<TtsSyncCueEntity>

    @Query(
        """
        SELECT * FROM tts_sync_cues 
        WHERE book_id = :bookId 
          AND (:audioTrackId IS NULL OR audio_track_id = :audioTrackId)
          AND start_time_ms <= :positionMs 
          AND end_time_ms >= :positionMs 
        LIMIT 1
        """
    )
    suspend fun getActiveCueAtTime(
        bookId: String,
        audioTrackId: String?,
        positionMs: Long
    ): TtsSyncCueEntity?

    @Query(
        """
        SELECT * FROM tts_sync_cues 
        WHERE book_id = :bookId 
          AND chapter_index = :chapterIndex 
          AND start_char_offset <= :charOffset 
          AND end_char_offset >= :charOffset 
        LIMIT 1
        """
    )
    suspend fun getCueAtCharOffset(
        bookId: String,
        chapterIndex: Int,
        charOffset: Int
    ): TtsSyncCueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCues(cues: List<TtsSyncCueEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCue(cue: TtsSyncCueEntity)

    @Query("DELETE FROM tts_sync_cues WHERE book_id = :bookId AND chapter_index = :chapterIndex")
    suspend fun deleteCuesForChapter(bookId: String, chapterIndex: Int)

    @Query("DELETE FROM tts_sync_cues WHERE book_id = :bookId")
    suspend fun deleteCuesForBook(bookId: String)
}
