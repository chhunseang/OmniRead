# Changelog

All notable changes to OmniRead are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [0.0.1-alpha] - 2026-11-11

The first public alpha.

### Reader
- EPUB 2/3 reader with the book's own CSS, embedded (incl. obfuscated) fonts and inline images; MOBI/AZW3, FB2, TXT and PDF support.
- Paged and continuous modes, tap zones, per-side margins, typography controls, themes and paper textures.
- Rubber-band "pull to next chapter" with haptic snap; live page slider with per-page haptics.
- Status line with battery, clock, chapter and page.
- Read aloud with the Studio narrator.

### Library
- Watched folders (recursive), single-file import, cover extraction, metadata editing, search.
- Continue reading card; Reading / Not started / Finished / Favorites filters.

### Listen
- Audiobook player for M4B/M4A/MP3 files and folders, with mini player and full Now Playing view.
- Embedded chapter markers read from M4B/M4A (QuickTime chapter tracks and Nero `chpl`).
- Speed, sleep timer with shake-to-extend, bookmarks with notes, skip silence, voice boost.
- Rewind on resume and configurable skip intervals.
- "Your files" and "Made in Studio" shelves, Studio badge, long-press actions.

### Studio
- On-device Kokoro-82M narration (53 voices) via sherpa-onnx; optional OpenAI / ElevenLabs / Gemini voices with your own key.
- Narrator picker with filters and custom preview text; chapter picker with "story only" default.
- M4B (AAC) or WAV export with chapter markers and follow-along sentence timings.
- Background narration with progress notification and time-left estimate.
- Share, save to phone and delete narrations.

### Settings
- Theme and Material You colours, reading defaults, listening behaviour, narration, folders, storage and cache.

### Known limitations
- 64-bit ARM phones only.
- Narration runs at roughly half real-time on a Pixel 6.
- The Studio job list is not kept across app restarts (finished audiobooks are).
- PDF support is basic.

[0.0.1-alpha]: https://github.com/chhunseang/OmniRead/releases/tag/v0.0.1-alpha
