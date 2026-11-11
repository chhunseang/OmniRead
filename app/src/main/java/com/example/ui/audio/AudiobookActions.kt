package com.example.ui.audio

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.ReadingStatus
import com.example.data.local.entity.isStudioMade
import com.example.data.local.entity.studioVoiceName
import com.example.ui.library.BookCover
import com.example.ui.library.StoragePaths

/** The little waveform pill that marks a Studio narration (same icon as the Studio tab). */
@Composable
fun StudioBadge(modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = CircleShape,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = if (compact) 5.dp else 8.dp, vertical = 3.dp)
        ) {
            Icon(Icons.Default.GraphicEq, null, Modifier.size(13.dp))
            if (!compact) {
                Spacer(Modifier.width(4.dp))
                Text("Studio", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** One line that says where an audiobook came from. */
fun audiobookOriginLine(book: BookEntity): String =
    if (book.isStudioMade) {
        listOfNotNull("Made in Studio", book.studioVoiceName?.let { "read by $it" }).joinToString(" · ")
    } else {
        listOfNotNull("Your file", book.narrator?.takeIf { it.isNotBlank() }?.let { "read by $it" }).joinToString(" · ")
    }

/**
 * Long-press actions for an audiobook. Studio narrations can be shared, saved to the phone's
 * storage or deleted; audiobooks you added can be taken out of Listen (the file stays).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookActionsSheet(
    book: BookEntity,
    viewModel: AudiobookPlayerViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val studio = book.isStudioMade
    var confirmDelete by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val saveM4b = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        if (uri != null) viewModel.saveToDevice(book, uri.toString())
        onDismiss()
    }
    val saveWav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) viewModel.saveToDevice(book, uri.toString())
        onDismiss()
    }

    val finished = book.readingStatus == ReadingStatus.COMPLETED || book.progressionPercentage >= 0.98f
    val started = book.progressionPercentage > 0.005f

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        audiobookOriginLine(book),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (studio) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        listOfNotNull(
                            book.totalDurationMs.takeIf { it > 0 }?.let { formatDurationWords(it) },
                            viewModel.fileSizeBytes(book).takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                            if (!studio) StoragePaths.readable(book.fileUri).substringAfterLast('/').takeIf { it.isNotBlank() } else null
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            ActionRow(Icons.Outlined.PlayCircle, if (started && !finished) "Resume" else "Play") {
                viewModel.openAudiobook(book.id); onDismiss()
            }
            if (studio) {
                ActionRow(Icons.Outlined.Share, "Share", "Send the audio to another app or person") {
                    viewModel.shareIntent(book)?.let { context.startActivity(it) }
                    onDismiss()
                }
                ActionRow(Icons.Outlined.Download, "Save to phone", "Keep a copy in Downloads or any folder") {
                    val name = viewModel.suggestedFileName(book)
                    if (name.endsWith(".wav", ignoreCase = true)) saveWav.launch(name) else saveM4b.launch(name)
                }
            }
            if (finished || started) {
                ActionRow(Icons.Outlined.Replay, "Start over") { viewModel.setFinished(book, false); onDismiss() }
            }
            if (!finished) {
                ActionRow(Icons.Outlined.CheckCircle, "Mark as finished") { viewModel.setFinished(book, true); onDismiss() }
            }
            if (studio) {
                ActionRow(Icons.Outlined.Delete, "Delete", "Removes the narration and its audio file", danger = true) { confirmDelete = true }
            } else {
                ActionRow(Icons.Outlined.RemoveCircleOutline, "Remove from Listen", "Your file stays on the phone") { confirmDelete = true }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (studio) "Delete this narration?" else "Remove from Listen?") },
            text = {
                Text(
                    if (studio) "\"${book.title}\" and its audio file will be deleted from this phone. You can narrate it again in the Studio any time."
                    else "\"${book.title}\" leaves Listen along with its bookmarks and progress. The audio file itself isn't touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteOrRemove(book)
                    confirmDelete = false
                    onDismiss()
                }) { Text(if (studio) "Delete" else "Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(title, color = tint) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = { Icon(icon, null, tint = if (danger) tint else MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    )
}
