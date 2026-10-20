package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.local.entity.AnnotationStyle
import com.example.data.local.entity.BookFormat
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.ReadingStatus
import com.example.data.local.entity.TtsEngineType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OmniReadTypeConverters {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @TypeConverter
    fun fromStringList(value: List<String>?): String {
        return json.encodeToString(value ?: emptyList())
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun fromFloatList(value: List<Float>?): String {
        return json.encodeToString(value ?: emptyList())
    }

    @TypeConverter
    fun toFloatList(value: String?): List<Float> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Float>>(value) }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun fromBookFormat(format: BookFormat): String = format.name

    @TypeConverter
    fun toBookFormat(value: String): BookFormat =
        runCatching { BookFormat.valueOf(value) }.getOrDefault(BookFormat.EPUB)

    @TypeConverter
    fun fromMediaType(mediaType: MediaType): String = mediaType.name

    @TypeConverter
    fun toMediaType(value: String): MediaType =
        runCatching { MediaType.valueOf(value) }.getOrDefault(MediaType.EBOOK)

    @TypeConverter
    fun fromReadingStatus(status: ReadingStatus): String = status.name

    @TypeConverter
    fun toReadingStatus(value: String): ReadingStatus =
        runCatching { ReadingStatus.valueOf(value) }.getOrDefault(ReadingStatus.UNREAD)

    @TypeConverter
    fun fromAnnotationStyle(style: AnnotationStyle): String = style.name

    @TypeConverter
    fun toAnnotationStyle(value: String): AnnotationStyle =
        runCatching { AnnotationStyle.valueOf(value) }.getOrDefault(AnnotationStyle.HIGHLIGHT)

    @TypeConverter
    fun fromTtsEngineType(engine: TtsEngineType): String = engine.name

    @TypeConverter
    fun toTtsEngineType(value: String): TtsEngineType =
        runCatching { TtsEngineType.valueOf(value) }.getOrDefault(TtsEngineType.LOCAL_KOKORO)
}
