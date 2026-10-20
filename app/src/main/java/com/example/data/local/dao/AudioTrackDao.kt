package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.AudioTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioTrackDao {
    @Query("SELECT * FROM audio_tracks WHERE book_id = :bookId ORDER BY track_index ASC")
    fun observeTracksForBook(bookId: String): Flow<List<AudioTrackEntity>>

    @Query("SELECT * FROM audio_tracks WHERE book_id = :bookId ORDER BY track_index ASC")
    suspend fun getTracksForBook(bookId: String): List<AudioTrackEntity>

    @Query("SELECT * FROM audio_tracks WHERE book_id = :bookId AND track_index = :trackIndex LIMIT 1")
    suspend fun getTrackByIndex(bookId: String, trackIndex: Int): AudioTrackEntity?

    @Query("SELECT * FROM audio_tracks WHERE id = :trackId LIMIT 1")
    suspend fun getTrackById(trackId: String): AudioTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<AudioTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: AudioTrackEntity)

    @Update
    suspend fun updateTrack(track: AudioTrackEntity)

    @Query("DELETE FROM audio_tracks WHERE book_id = :bookId")
    suspend fun deleteTracksForBook(bookId: String)
}
