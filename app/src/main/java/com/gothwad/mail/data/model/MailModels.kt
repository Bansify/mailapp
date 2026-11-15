package com.gothwad.mail.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class MailFolder(val displayName: String) {
    ALL_INBOXES("All inboxes"),
    PRIMARY("Primary"),
    PROMOTIONS("Promotions"),
    SOCIAL("Social"),
    UPDATES("Updates"),
    STARRED("Starred"),
    SNOOZED("Snoozed"),
    IMPORTANT("Important"),
    SENT("Sent"),
    SCHEDULED("Scheduled"),
    DRAFT("Drafts"),
    ALL_MAIL("All mail"),
    SPAM("Spam"),
    TRASH("Trash");

    val isInboxCategory: Boolean
        get() = this == PRIMARY || this == PROMOTIONS || this == SOCIAL || this == UPDATES
}

enum class ThemePreference(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class SwipeAction(val label: String) {
    ARCHIVE("Archive"),
    DELETE("Delete"),
    MARK_READ("Mark as read/unread")
}

@Serializable
data class AttachmentItem(
    val id: String,
    val name: String,
    val sizeLabel: String,
    val mimeType: String,
    val uriString: String? = null
)

@Serializable
data class ThreadMessage(
    val id: String,
    val senderName: String,
    val senderEmail: String,
    val senderColorHex: Long,
    val recipientsTo: List<String>,
    val recipientsCc: List<String> = emptyList(),
    val body: String,
    val timestampMillis: Long,
    val isStarred: Boolean = false,
    val attachments: List<AttachmentItem> = emptyList(),
    val isEncryptedTls: Boolean = true
)

@Entity(tableName = "email_threads")
data class EmailThreadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountEmail: String,
    val subject: String,
    val snippet: String,
    val primarySenderName: String,
    val primarySenderEmail: String,
    val primarySenderColorHex: Long,
    val folder: MailFolder,
    val previousFolder: MailFolder = MailFolder.PRIMARY,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isImportant: Boolean = false,
    val isArchived: Boolean = false,
    val isConfidential: Boolean = false,
    val scheduledForMillis: Long? = null,
    val snoozedUntilLabel: String? = null,
    val labels: List<String> = emptyList(),
    val messages: List<ThreadMessage> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val messageCount: Int
        get() = messages.size

    val allAttachments: List<AttachmentItem>
        get() = messages.flatMap { it.attachments }

    val hasAttachments: Boolean
        get() = messages.any { it.attachments.isNotEmpty() }
}

@Entity(tableName = "accounts")
data class MailAccountEntity(
    @PrimaryKey val email: String,
    val displayName: String,
    val avatarColorHex: Long,
    val usedStorageGb: Float = 4.2f,
    val totalStorageGb: Float = 15.0f,
    val signature: String = "Sent from MailApp",
    val vacationEnabled: Boolean = false,
    val vacationMessage: String = "Thanks for your email. I am currently away and will respond soon.",
    val isActive: Boolean = false
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val rightSwipeAction: SwipeAction = SwipeAction.ARCHIVE,
    val leftSwipeAction: SwipeAction = SwipeAction.DELETE,
    val compactDensity: Boolean = false,
    val conversationView: Boolean = true,
    val confirmBeforeDelete: Boolean = false
)

data class MeetingSession(
    val id: String,
    val title: String,
    val code: String,
    val timeLabel: String,
    val hostName: String,
    val participantCount: Int
)
