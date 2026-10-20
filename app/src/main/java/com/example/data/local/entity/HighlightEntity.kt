package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["book_id", "chapter_index", "start_char_offset"]),
        Index(value = ["book_id", "created_at"])
    ]
)
data class HighlightEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "book_id")
    val bookId: String,

    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,

    @ColumnInfo(name = "chapter_href")
    val chapterHref: String,

    @ColumnInfo(name = "chapter_title")
    val chapterTitle: String,

    @ColumnInfo(name = "locator_json")
    val locatorJson: String,

    @ColumnInfo(name = "dom_start_cfi")
    val domStartCfi: String? = null,

    @ColumnInfo(name = "dom_end_cfi")
    val domEndCfi: String? = null,

    @ColumnInfo(name = "start_char_offset")
    val startCharOffset: Int,

    @ColumnInfo(name = "end_char_offset")
    val endCharOffset: Int,

    @ColumnInfo(name = "selected_text")
    val selectedText: String,

    @ColumnInfo(name = "surrounding_context")
    val surroundingContext: String? = null,

    @ColumnInfo(name = "color_argb")
    val colorArgb: Long = 0xFFFFEB3B,

    @ColumnInfo(name = "style")
    val style: AnnotationStyle = AnnotationStyle.HIGHLIGHT,

    @ColumnInfo(name = "note_markdown")
    val noteMarkdown: String? = null,

    @ColumnInfo(name = "tags")
    val tags: List<String> = emptyList(),

    @ColumnInfo(name = "progression_percentage")
    val progressionPercentage: Float = 0f,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
