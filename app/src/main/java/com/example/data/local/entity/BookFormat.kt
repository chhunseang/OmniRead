package com.example.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
enum class BookFormat(val extension: String, val mimeType: String, val isAudioFormat: Boolean) {
    EPUB("epub", "application/epub+zip", false),
    MOBI("mobi", "application/x-mobipocket-ebook", false),
    AZW3("azw3", "application/vnd.amazon.ebook", false),
    PDF("pdf", "application/pdf", false),
    TXT("txt", "text/plain", false),
    FB2("fb2", "application/x-fictionbook+xml", false),
    CBZ("cbz", "application/vnd.comicbook+zip", false),
    M4B("m4b", "audio/mp4", true),
    MP3("mp3", "audio/mpeg", true),
    M4A("m4a", "audio/mp4", true),
    FLAC("flac", "audio/flac", true),
    OGG("ogg", "audio/ogg", true),
    AUDIO_FOLDER("folder", "inode/directory", true);

    companion object {
        fun fromExtension(ext: String): BookFormat {
            val clean = ext.trim().lowercase().removePrefix(".")
            return entries.firstOrNull { it.extension == clean } ?: EPUB
        }
    }
}

@Serializable
enum class MediaType {
    EBOOK,
    AUDIOBOOK,
    HYBRID_SYNCED
}

@Serializable
enum class ReadingStatus {
    UNREAD,
    READING,
    COMPLETED,
    ABANDONED
}

@Serializable
enum class AnnotationStyle {
    HIGHLIGHT,
    UNDERLINE,
    STRIKETHROUGH
}

@Serializable
enum class TtsEngineType {
    LOCAL_KOKORO,
    CLOUD_OPENAI,
    CLOUD_ELEVENLABS,
    CLOUD_GEMINI
}
