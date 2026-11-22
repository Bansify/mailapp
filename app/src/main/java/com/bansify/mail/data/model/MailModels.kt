package com.bansify.mail.data.model

import androidx.room.Entity
import androidx.room.Index
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

/**
 * The Mail Worker exposes four listable mailboxes. Cached folders map onto them so a local folder
 * can always be refreshed from a single server query.
 */
enum class ServerFolder(val wireValue: String) {
    INBOX("inbox"),
    STARRED("starred"),
    SENT("sent"),
    DRAFTS("drafts"),
    TRASH("trash");

    companion object {
        /**
         * Maps a UI folder to the server folder that lists it: all inbox categories and "all mail"
         * read `inbox`, starred reads `starred`, and so on.
         */
        fun forMailFolder(folder: MailFolder): ServerFolder = when (folder) {
            MailFolder.ALL_INBOXES,
            MailFolder.PRIMARY,
            MailFolder.PROMOTIONS,
            MailFolder.SOCIAL,
            MailFolder.UPDATES,
            MailFolder.IMPORTANT,
            MailFolder.ALL_MAIL -> INBOX
            MailFolder.STARRED -> STARRED
            MailFolder.SENT, MailFolder.SCHEDULED -> SENT
            MailFolder.DRAFT -> DRAFTS
            MailFolder.TRASH -> TRASH
            // The Worker has no snooze or archive mailbox; those messages live in the inbox.
            MailFolder.SNOOZED, MailFolder.SPAM -> INBOX
        }

        /** Parses a server folder value, defaulting to `inbox`. */
        fun fromWire(value: String?): ServerFolder =
            entries.find { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: INBOX
    }
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

/** Retention choices offered by Settings, mapped to `PATCH /api/mail/settings`. */
enum class RetentionPolicy(val serverValue: Int, val label: String) {
    KEEP_500(500, "500 messages"),
    KEEP_1500(1500, "1,500 messages"),
    STORE_ALL(0, "Store All (up to 50 MB)");

    companion object {
        fun fromServer(value: Int): RetentionPolicy =
            entries.find { it.serverValue == value } ?: KEEP_500
    }
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

/**
 * One cached server message for one account.
 *
 * The cache is keyed by the server message id (`serverId`), and every flow is scoped by
 * `accountEmail`, so Account A's mail can never be shown while Account B is active.
 */
@Entity(
    tableName = "email_threads",
    indices = [
        Index(value = ["accountEmail", "serverId"], unique = true),
        Index(value = ["accountEmail", "folder"]),
        Index(value = ["accountEmail", "updatedAtMillis"]),
    ],
)
data class EmailThreadEntity(
    @PrimaryKey val id: String,
    val accountEmail: String,
    val serverId: String,
    val subject: String,
    val snippet: String,
    val primarySenderName: String,
    val primarySenderEmail: String,
    val primarySenderColorHex: Long,
    val folder: MailFolder,
    val previousFolder: MailFolder = folder,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val isImportant: Boolean = false,
    val isArchived: Boolean = false,
    val isConfidential: Boolean = false,
    val scheduledForMillis: Long? = null,
    val snoozedUntilLabel: String? = null,
    val labels: List<String> = emptyList(),
    val messages: List<ThreadMessage> = emptyList(),
    /** Body downloaded from `GET /api/mail/messages/:id`; `bodyLoaded` marks a complete fetch. */
    val bodyText: String = "",
    val bodyHtml: String? = null,
    val etag: String? = null,
    val bodyLoaded: Boolean = false,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val messageCount: Int
        get() = messages.size

    val allAttachments: List<AttachmentItem>
        get() = messages.flatMap { it.attachments }

    val hasAttachments: Boolean
        get() = messages.any { it.attachments.isNotEmpty() }

    /** Composite key used by every Room write so the cache never crosses accounts. */
    val cacheKey: String
        get() = cacheKeyFor(accountEmail, serverId)

    companion object {
        /** Deterministic primary key for `(accountEmail, serverId)`. */
        fun cacheKeyFor(accountEmail: String, serverId: String): String =
            "${accountEmail.lowercase()}::$serverId"

        /** Falls back to a synthetic id when the Worker has not assigned one yet. */
        fun serverIdOrFallback(serverId: String?, accountEmail: String): String =
            serverId?.takeIf { it.isNotBlank() }
                ?: "local-${accountEmail.lowercase()}-${System.nanoTime()}"
    }
}

@Entity(tableName = "accounts")
data class MailAccountEntity(
    @PrimaryKey val email: String,
    val userId: String = "",
    val username: String = "",
    val displayName: String,
    val avatarColorHex: Long,
    /** Storage meter values, converted from the Worker's byte counts. */
    val storageUsedBytes: Long = 0L,
    val storageQuotaBytes: Long = 52_428_800L,
    val totalMessages: Int = 0,
    val starredMessages: Int = 0,
    val retentionLimit: Int = 500,
    val unreadCount: Int = 0,
    val signature: String = "Sent from Bansify Mail",
    val vacationEnabled: Boolean = false,
    val vacationMessage: String = "Thanks for your email. I am currently away and will respond soon.",
    val isActive: Boolean = false
) {
    val usedStorageLabel: String
        get() = com.bansify.mail.data.remote.formatStorageBytes(storageUsedBytes)

    val totalStorageLabel: String
        get() = com.bansify.mail.data.remote.formatStorageBytes(storageQuotaBytes)

    val storageProgress: Float
        get() = if (storageQuotaBytes <= 0L) 0f
        else (storageUsedBytes.toFloat() / storageQuotaBytes.toFloat()).coerceIn(0f, 1f)

    val retention: RetentionPolicy
        get() = RetentionPolicy.fromServer(retentionLimit)
}

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
