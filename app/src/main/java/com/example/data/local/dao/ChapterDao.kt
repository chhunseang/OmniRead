package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY chapter_index ASC")
    fun observeChaptersForBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY chapter_index ASC")
    suspend fun getChaptersForBook(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId AND chapter_index = :chapterIndex LIMIT 1")
    suspend fun getChapterByIndex(bookId: String, chapterIndex: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE id = :chapterId LIMIT 1")
    suspend fun getChapterById(chapterId: String): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapter(chapter: ChapterEntity)

    @Update
    suspend fun updateChapter(chapter: ChapterEntity)

    @Query(
        """
        UPDATE chapters 
        SET is_tts_converted = :converted,
            converted_audio_path = :audioPath,
            start_time_ms = :startTimeMs,
            end_time_ms = :endTimeMs
        WHERE id = :chapterId
        """
    )
    suspend fun markChapterTtsConverted(
        chapterId: String,
        converted: Boolean,
        audioPath: String?,
        startTimeMs: Long?,
        endTimeMs: Long?
    )

    @Query("DELETE FROM chapters WHERE book_id = :bookId")
    suspend fun deleteChaptersForBook(bookId: String)
}
