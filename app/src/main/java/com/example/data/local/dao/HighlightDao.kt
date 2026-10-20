package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.AnnotationStyle
import com.example.data.local.entity.HighlightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE book_id = :bookId ORDER BY chapter_index ASC, start_char_offset ASC")
    fun observeHighlightsForBook(bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE book_id = :bookId AND chapter_index = :chapterIndex ORDER BY start_char_offset ASC")
    fun observeHighlightsForChapter(bookId: String, chapterIndex: Int): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE book_id = :bookId ORDER BY chapter_index ASC, start_char_offset ASC")
    suspend fun getHighlightsForBook(bookId: String): List<HighlightEntity>

    @Query(
        """
        SELECT * FROM highlights 
        WHERE book_id = :bookId 
          AND (selected_text LIKE '%' || :query || '%' OR note_markdown LIKE '%' || :query || '%')
        ORDER BY chapter_index ASC, start_char_offset ASC
        """
    )
    fun searchHighlightsInBook(bookId: String, query: String): Flow<List<HighlightEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHighlight(highlight: HighlightEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHighlights(highlights: List<HighlightEntity>)

    @Update
    suspend fun updateHighlight(highlight: HighlightEntity)

    @Query(
        """
        UPDATE highlights 
        SET color_argb = :colorArgb,
            style = :style,
            note_markdown = :noteMarkdown,
            updated_at = :updatedAt
        WHERE id = :highlightId
        """
    )
    suspend fun updateHighlightAppearanceAndNote(
        highlightId: String,
        colorArgb: Long,
        style: AnnotationStyle,
        noteMarkdown: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Delete
    suspend fun deleteHighlight(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :highlightId")
    suspend fun deleteHighlightById(highlightId: String)

    @Query("DELETE FROM highlights WHERE book_id = :bookId")
    suspend fun deleteHighlightsForBook(bookId: String)
}
