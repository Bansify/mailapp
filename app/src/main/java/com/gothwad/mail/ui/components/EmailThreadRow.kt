package com.gothwad.mail.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LabelImportant
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.data.model.SwipeAction
import com.gothwad.mail.ui.theme.ArchiveTeal
import com.gothwad.mail.ui.theme.DeleteRed
import com.gothwad.mail.ui.theme.DraftRed
import com.gothwad.mail.ui.theme.ImportantYellow
import com.gothwad.mail.ui.theme.SocialBlue
import com.gothwad.mail.ui.theme.StarGold

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EmailThreadRow(
    thread: EmailThreadEntity,
    isSelected: Boolean,
    isTabletActive: Boolean = false,
    compactDensity: Boolean = false,
    rightSwipeAction: SwipeAction = SwipeAction.ARCHIVE,
    leftSwipeAction: SwipeAction = SwipeAction.DELETE,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onToggleStar: () -> Unit,
    onAttachmentClick: (AttachmentItem) -> Unit,
    onSwipe: (SwipeAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onSwipe(rightSwipeAction)
                    rightSwipeAction != SwipeAction.MARK_READ
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onSwipe(leftSwipeAction)
                    leftSwipeAction != SwipeAction.MARK_READ
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.testTag("email_row_${thread.id}"),
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val action = if (direction == SwipeToDismissBoxValue.StartToEnd) rightSwipeAction else leftSwipeAction
            val bgColor by animateColorAsState(
                targetValue = when (direction) {
                    SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.surface
                    else -> when (action) {
                        SwipeAction.ARCHIVE -> ArchiveTeal
                        SwipeAction.DELETE -> DeleteRed
                        SwipeAction.MARK_READ -> SocialBlue
                    }
                },
                label = "swipe_bg"
            )
            val icon = when (action) {
                SwipeAction.ARCHIVE -> Icons.Rounded.Archive
                SwipeAction.DELETE -> Icons.Rounded.DeleteOutline
                SwipeAction.MARK_READ -> Icons.Rounded.MarkEmailRead
            }
            val alignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Icon(
                        imageVector = icon,
                        contentDescription = action.label,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    ) {
        val rowBg = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            isTabletActive -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            else -> MaterialTheme.colorScheme.surface
        }
        val verticalPadding = if (compactDensity) 8.dp else 12.dp
        val isUnread = !thread.isRead
        val senderWeight = if (isUnread) FontWeight.Bold else FontWeight.Normal
        val subjectWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBg)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(start = 12.dp, end = 8.dp, top = verticalPadding, bottom = verticalPadding),
            verticalAlignment = Alignment.Top
        ) {
            SenderAvatar(
                name = thread.primarySenderName,
                colorHex = thread.primarySenderColorHex,
                isSelected = isSelected,
                size = 40.dp,
                onClick = onAvatarClick,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Top Line: Sender + badges + Timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (thread.isImportant) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.LabelImportant,
                            contentDescription = "Important",
                            tint = ImportantYellow,
                            modifier = Modifier
                                .size(17.dp)
                                .padding(end = 3.dp)
                        )
                    }
                    if (thread.folder == MailFolder.DRAFT) {
                        Text(
                            text = "Draft",
                            style = MaterialTheme.typography.titleMedium,
                            color = DraftRed,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    } else {
                        Text(
                            text = thread.primarySenderName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = senderWeight,
                            color = if (isUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    if (thread.messageCount > 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = thread.messageCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = MailFormatters.formatListTimestamp(thread.updatedAtMillis),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Normal,
                        color = if (isUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Subject + Star Icon Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = thread.subject,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = subjectWeight,
                            color = if (isUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        if (thread.snoozedUntilLabel != null && thread.folder == MailFolder.SNOOZED) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = ImportantYellow,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Snoozed until ${thread.snoozedUntilLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ImportantYellow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Text(
                                text = thread.snippet,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onToggleStar,
                        modifier = Modifier.testTag("star_button_${thread.id}")
                    ) {
                        Icon(
                            imageVector = if (thread.isStarred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                            contentDescription = "Toggle star",
                            tint = if (thread.isStarred) StarGold else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Optional Attachment Chips row (like modern Gmail!)
                if (!compactDensity && thread.allAttachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        thread.allAttachments.take(3).forEach { att ->
                            AttachmentPreviewChip(
                                attachment = att,
                                onClick = { onAttachmentClick(att) }
                            )
                        }
                    }
                }
            }
        }
    }
}
