package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.ReadingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(last_opened_at, added_at) DESC")
    fun observeAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE media_type = :mediaType ORDER BY COALESCE(last_opened_at, added_at) DESC")
    fun observeBooksByMediaType(mediaType: MediaType): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE reading_status = :status ORDER BY COALESCE(last_opened_at, added_at) DESC")
    fun observeBooksByStatus(status: ReadingStatus): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE is_favorite = 1 ORDER BY COALESCE(last_opened_at, added_at) DESC")
    fun observeFavoriteBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE series_name IS NOT NULL AND series_name != '' ORDER BY series_name ASC, series_index ASC")
    fun observeSeriesBooks(): Flow<List<BookEntity>>

    @Query(
        """
        SELECT * FROM books 
        WHERE title LIKE '%' || :query || '%' 
           OR author LIKE '%' || :query || '%' 
           OR series_name LIKE '%' || :query || '%'
           OR tags LIKE '%' || :query || '%'
        ORDER BY COALESCE(last_opened_at, added_at) DESC
        """
    )
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    fun observeBookById(bookId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBookById(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE file_uri = :fileUri LIMIT 1")
    suspend fun getBookByUri(fileUri: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBooks(books: List<BookEntity>)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query(
        """
        UPDATE books 
        SET current_chapter_index = :chapterIndex,
            current_locator_json = :locatorJson,
            current_char_offset = :charOffset,
            progression_percentage = :progression,
            reading_status = :status,
            last_opened_at = :timestamp
        WHERE id = :bookId
        """
    )
    suspend fun updateReadingPosition(
        bookId: String,
        chapterIndex: Int,
        locatorJson: String?,
        charOffset: Int,
        progression: Float,
        status: ReadingStatus,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE books 
        SET current_audio_track_index = :trackIndex,
            current_audio_position_ms = :positionMs,
            progression_percentage = :progression,
            reading_status = :status,
            last_opened_at = :timestamp
        WHERE id = :bookId
        """
    )
    suspend fun updateAudioPosition(
        bookId: String,
        trackIndex: Int,
        positionMs: Long,
        progression: Float,
        status: ReadingStatus,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE books 
        SET playback_speed = :speed,
            playback_pitch = :pitch,
            skip_silence_enabled = :skipSilence,
            voice_boost_enabled = :voiceBoost
        WHERE id = :bookId
        """
    )
    suspend fun updateAudioPlaybackSettings(
        bookId: String,
        speed: Float,
        pitch: Float,
        skipSilence: Boolean,
        voiceBoost: Boolean
    )

    @Query(
        """
        UPDATE books 
        SET media_type = :mediaType,
            synced_audiobook_uri = :audiobookUri,
            synced_sidecar_path = :sidecarPath
        WHERE id = :bookId
        """
    )
    suspend fun attachSyncedAudiobook(
        bookId: String,
        mediaType: MediaType = MediaType.HYBRID_SYNCED,
        audiobookUri: String,
        sidecarPath: String?
    )

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: String)
}
