package com.example.data.local.entity

/** Tag carried by audiobooks narrated in the Studio. */
const val STUDIO_TAG = "studio"

/** An audiobook narrated in the Studio (as opposed to an audio file you added yourself). */
val BookEntity.isStudioMade: Boolean
    get() = mediaType == MediaType.AUDIOBOOK && (STUDIO_TAG in tags || id.contains("_audiobook_"))

/** Something you listen to (shown in Listen); everything else is a book you read (shown in Library). */
val BookEntity.isAudiobook: Boolean
    get() = mediaType == MediaType.AUDIOBOOK

/**
 * The narrator as people say it: "Kokoro · bm_fable" → "Fable", "OpenAI · alloy" → "Alloy".
 */
val BookEntity.studioVoiceName: String?
    get() {
        val raw = narrator ?: return null
        val voice = raw.substringAfter("·", raw).trim()
        if (voice.isBlank()) return raw.trim()
        val name = if (voice.length > 3 && voice[2] == '_') voice.substring(3) else voice
        return name.replaceFirstChar { it.uppercase() }
    }
