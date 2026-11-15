package com.gothwad.mail.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.automirrored.rounded.LabelImportant
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.ReplyAll
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ReportGmailerrorred
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.data.model.ThreadMessage
import com.gothwad.mail.ui.components.AttachmentPreviewChip
import com.gothwad.mail.ui.components.AttachmentViewerDialog
import com.gothwad.mail.ui.components.LabelPill
import com.gothwad.mail.ui.components.MailFormatters
import com.gothwad.mail.ui.components.SenderAvatar
import com.gothwad.mail.ui.theme.ImportantYellow
import com.gothwad.mail.ui.theme.JetBrainsMonoFamily
import com.gothwad.mail.ui.theme.PromotionsGreen
import com.gothwad.mail.ui.theme.StarGold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadDetailScreen(
    thread: EmailThreadEntity?,
    availableLabels: List<String>,
    showBackButton: Boolean = true,
    onBack: () -> Unit,
    onArchive: (EmailThreadEntity) -> Unit,
    onDelete: (EmailThreadEntity) -> Unit,
    onRestore: (EmailThreadEntity) -> Unit,
    onMarkUnread: (EmailThreadEntity) -> Unit,
    onToggleStar: (EmailThreadEntity) -> Unit,
    onToggleImportant: (EmailThreadEntity) -> Unit,
    onReportSpam: (EmailThreadEntity) -> Unit,
    onSnooze: (EmailThreadEntity, String) -> Unit,
    onUpdateLabels: (EmailThreadEntity, List<String>) -> Unit,
    onReply: (EmailThreadEntity) -> Unit,
    onReplyAll: (EmailThreadEntity) -> Unit,
    onForward: (EmailThreadEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (thread == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Select an email to read",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSnoozeDialog by remember { mutableStateOf(false) }
    var showLabelsDialog by remember { mutableStateOf(false) }
    var previewAttachment by remember { mutableStateOf<AttachmentItem?>(null) }

    // Latest message is expanded by default; earlier messages collapsed unless toggled
    val expandedState = remember(thread.id, thread.messages.size) {
        mutableStateMapOf<String, Boolean>().apply {
            thread.messages.forEachIndexed { index, msg ->
                this[msg.id] = index == thread.messages.lastIndex
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("detail_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Navigate back"
                            )
                        }
                    }
                },
                title = {},
                actions = {
                    if (thread.folder == MailFolder.TRASH || thread.folder == MailFolder.SPAM) {
                        IconButton(
                            onClick = {
                                onRestore(thread)
                                if (showBackButton) onBack()
                            },
                            modifier = Modifier.testTag("detail_restore_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RestoreFromTrash,
                                contentDescription = "Restore from ${thread.folder.displayName}"
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                onArchive(thread)
                                if (showBackButton) onBack()
                            },
                            modifier = Modifier.testTag("detail_archive_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Archive,
                                contentDescription = "Archive"
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            onDelete(thread)
                            if (showBackButton) onBack()
                        },
                        modifier = Modifier.testTag("detail_delete_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete"
                        )
                    }

                    IconButton(
                        onClick = {
                            onMarkUnread(thread)
                            if (showBackButton) onBack()
                        },
                        modifier = Modifier.testTag("detail_mark_unread_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MarkEmailUnread,
                            contentDescription = "Mark as unread"
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.testTag("detail_more_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More options"
                            )
                        }

                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Snooze") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Schedule, contentDescription = null)
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showSnoozeDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Change labels") },
                                onClick = {
                                    showOverflowMenu = false
                                    showLabelsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (thread.isImportant) "Mark not important" else "Mark important")
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Rounded.LabelImportant, contentDescription = null)
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onToggleImportant(thread)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Report spam") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.ReportGmailerrorred, contentDescription = null)
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onReportSpam(thread)
                                    if (showBackButton) onBack()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onReply(thread) },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("detail_reply_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Reply,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reply", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onReplyAll(thread) },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("detail_reply_all_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ReplyAll,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reply all", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onForward(thread) },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("detail_forward_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Forward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Forward", maxLines = 1)
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Subject + Star + Label Pills Header
            item(key = "subject_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = thread.subject,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onToggleStar(thread) },
                            modifier = Modifier.testTag("detail_star_button")
                        ) {
                            Icon(
                                imageVector = if (thread.isStarred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                contentDescription = "Toggle star",
                                tint = if (thread.isStarred) StarGold else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (thread.isImportant) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.LabelImportant,
                                contentDescription = "Important",
                                tint = ImportantYellow,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        LabelPill(label = thread.folder.displayName)
                        thread.labels.forEach { label ->
                            LabelPill(label = label)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Messages in conversation thread
            itemsIndexed(
                items = thread.messages,
                key = { _, msg -> msg.id }
            ) { index, message ->
                val isExpanded = expandedState[message.id] ?: (index == thread.messages.lastIndex)
                ThreadMessageCard(
                    message = message,
                    isExpanded = isExpanded,
                    onToggleExpand = { expandedState[message.id] = !isExpanded },
                    onReply = { onReply(thread) },
                    onReplyAll = { onReplyAll(thread) },
                    onForward = { onForward(thread) },
                    onAttachmentClick = { previewAttachment = it }
                )
                if (index < thread.messages.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    previewAttachment?.let { att ->
        AttachmentViewerDialog(
            attachment = att,
            onDismiss = { previewAttachment = null }
        )
    }

    if (showSnoozeDialog) {
        val options = listOf("Later today · 6:00 PM", "Tomorrow · 8:00 AM", "This weekend · Sat 9:00 AM", "Next week · Mon 8:00 AM")
        AlertDialog(
            onDismissRequest = { showSnoozeDialog = false },
            title = { Text("Snooze until...") },
            text = {
                Column {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSnoozeDialog = false
                                    onSnooze(thread, option)
                                    if (showBackButton) onBack()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(option, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSnoozeDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showLabelsDialog) {
        val selected = remember(thread.labels) {
            mutableStateMapOf<String, Boolean>().apply {
                availableLabels.forEach { this[it] = it in thread.labels }
            }
        }
        AlertDialog(
            onDismissRequest = { showLabelsDialog = false },
            title = { Text("Label as") },
            text = {
                Column {
                    availableLabels.forEach { label ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected[label] = !(selected[label] ?: false) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected[label] ?: false,
                                onCheckedChange = { selected[label] = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = selected.filterValues { it }.keys.toList()
                    onUpdateLabels(thread, chosen)
                    showLabelsDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLabelsDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ThreadMessageCard(
    message: ThreadMessage,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
    onAttachmentClick: (AttachmentItem) -> Unit
) {
    var showMetadataDetails by remember { mutableStateOf(false) }
    var showMessageMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Sender Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SenderAvatar(
                name = message.senderName,
                colorHex = message.senderColorHex,
                size = 40.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = MailFormatters.formatListTimestamp(message.timestampMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isExpanded) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showMetadataDetails = !showMetadataDetails }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "to ${message.recipientsTo.joinToString(", ") { it.substringBefore("@") }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = if (showMetadataDetails) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "Toggle message headers",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Text(
                        text = message.body.replace("\n", " "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isExpanded) {
                IconButton(onClick = onReply) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Reply,
                        contentDescription = "Reply to message",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { showMessageMenu = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Message options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMessageMenu,
                        onDismissRequest = { showMessageMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Reply") },
                            onClick = { showMessageMenu = false; onReply() }
                        )
                        DropdownMenuItem(
                            text = { Text("Reply all") },
                            onClick = { showMessageMenu = false; onReplyAll() }
                        )
                        DropdownMenuItem(
                            text = { Text("Forward") },
                            onClick = { showMessageMenu = false; onForward() }
                        )
                    }
                }
            }
        }

        // Expandable "to me" Header Metadata Box
        AnimatedVisibility(visible = isExpanded && showMetadataDetails) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(14.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetadataLine(label = "From", value = "${message.senderName} <${message.senderEmail}>")
                    MetadataLine(label = "To", value = message.recipientsTo.joinToString(", "))
                    if (message.recipientsCc.isNotEmpty()) {
                        MetadataLine(label = "Cc", value = message.recipientsCc.joinToString(", "))
                    }
                    MetadataLine(
                        label = "Date",
                        value = MailFormatters.formatFullTimestamp(message.timestampMillis)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (message.isEncryptedTls) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            contentDescription = null,
                            tint = if (message.isEncryptedTls) PromotionsGreen else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (message.isEncryptedTls) "Standard encryption (TLS)" else "Unencrypted sender",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Expanded Body + Attachments
        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(top = 14.dp)) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (message.attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "${message.attachments.size} attachment${if (message.attachments.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        message.attachments.forEach { att ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { onAttachmentClick(att) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AttachmentPreviewChip(
                                    attachment = att,
                                    onClick = { onAttachmentClick(att) }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = att.sizeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
