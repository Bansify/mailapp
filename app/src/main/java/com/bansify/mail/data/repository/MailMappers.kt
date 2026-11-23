package com.bansify.mail.data.repository

import com.bansify.mail.data.model.AttachmentItem
import com.bansify.mail.data.model.EmailThreadEntity
import com.bansify.mail.data.model.MailAccountEntity
import com.bansify.mail.data.model.MailFolder
import com.bansify.mail.data.model.ServerFolder
import com.bansify.mail.data.model.ThreadMessage
import com.bansify.mail.data.remote.ApiMailSettings
import com.bansify.mail.data.remote.ApiMessage
import com.bansify.mail.data.remote.ApiSessionUser
import com.bansify.mail.auth.GothwadUserInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * Conversions between the Mail Worker wire format, the Bansify Accounts profile and the local
 * Room cache. Kept free of Android APIs so they can be unit tested on the JVM.
 */
object MailMappers {

    private val avatarPalette = longArrayOf(
        0xFF0B57D0, 0xFF1A73E8, 0xFFD93025, 0xFF188038,
        0xFF9334E6, 0xFFE37400, 0xFF00897B, 0xFF5F6368,
    )

    /** Stable avatar colour derived from the address, so a sender keeps the same colour. */
    fun avatarColor(seed: String): Long {
        if (seed.isEmpty()) return avatarPalette[0]
        var hash = 0
        seed.forEach { hash = (hash * 31 + it.code) }
        return avatarPalette[hash.absoluteValue % avatarPalette.size]
    }

    /** Converts an ISO-8601 instant from the Worker into epoch milliseconds. */
    fun parseInstantMillis(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching {
                // The Worker also emits `2024-05-01 12:00:00` style values from D1 defaults.
                java.time.LocalDateTime.parse(value.replace(' ', 'T').trimEnd('Z'))
                    .atZone(ZoneId.of("UTC"))
                    .toInstant()
                    .toEpochMilli()
            }
            .getOrDefault(System.currentTimeMillis())
    }

    /** Human label used by the thread rows and the detail header. */
    fun formatTimestamp(millis: Long, now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date = dateTime.toLocalDate()
        return when {
            date == today -> dateTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
            date == today.minusDays(1) -> "Yesterday"
            date.year == today.year -> dateTime.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
            else -> dateTime.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH))
        }
    }

    /** Maps a server message to the local cache row for [accountEmail]. */
    fun toEntity(
        message: ApiMessage,
        accountEmail: String,
        uiFolder: MailFolder,
        existing: EmailThreadEntity? = null,
    ): EmailThreadEntity {
        val senderEmail = message.from.email.ifBlank { "unknown@bansify.com" }
        val senderName = message.from.name?.takeIf { it.isNotBlank() }
            ?: senderEmail.substringBefore('@')
        val timestamp = parseInstantMillis(message.receivedAt)
        val subject = message.subject.ifBlank { "(no subject)" }
        val snippet = message.snippet.ifBlank { message.bodyText?.take(160).orEmpty() }
        val attachments = message.attachments.mapIndexed { index, attachment ->
            AttachmentItem(
                id = "${message.id}-att-$index",
                name = attachment.name.ifBlank { "attachment" },
                sizeLabel = formatSize(attachment.size),
                mimeType = attachment.type.ifBlank { "application/octet-stream" },
            )
        }
        val body = message.bodyText ?: existing?.bodyText.orEmpty()
        val threadMessage = ThreadMessage(
            id = message.id,
            senderName = senderName,
            senderEmail = senderEmail,
            senderColorHex = avatarColor(senderEmail),
            recipientsTo = message.to,
            recipientsCc = message.cc,
            body = body,
            timestampMillis = timestamp,
            isStarred = message.isStarred,
            attachments = attachments,
            bodyHtml = message.bodyHtml ?: existing?.bodyHtml,
        )
        return EmailThreadEntity(
            id = EmailThreadEntity.cacheKeyFor(accountEmail, message.id),
            accountEmail = accountEmail,
            serverId = message.id,
            subject = subject,
            snippet = snippet,
            primarySenderName = senderName,
            primarySenderEmail = senderEmail,
            primarySenderColorHex = avatarColor(senderEmail),
            folder = existing?.folder ?: uiFolder,
            previousFolder = existing?.previousFolder ?: uiFolder,
            isRead = message.isRead,
            isStarred = message.isStarred,
            isImportant = existing?.isImportant ?: false,
            isConfidential = existing?.isConfidential ?: false,
            labels = existing?.labels ?: emptyList(),
            messages = listOf(threadMessage),
            bodyText = body,
            bodyHtml = message.bodyHtml ?: existing?.bodyHtml,
            etag = existing?.etag,
            bodyLoaded = existing?.bodyLoaded ?: (message.bodyText != null),
            updatedAtMillis = timestamp,
        )
    }

    /** Applies a freshly downloaded body onto a cached row. */
    fun withBody(
        existing: EmailThreadEntity,
        message: ApiMessage,
        etag: String?,
    ): EmailThreadEntity {
        val bodyText = message.bodyText.orEmpty()
        val body = if (bodyText.isBlank() && !message.bodyHtml.isNullOrBlank()) {
            stripHtml(message.bodyHtml.orEmpty())
        } else {
            bodyText
        }
        val existingFirst = existing.messages.firstOrNull()
        val updatedMessage = existingFirst?.copy(
            body = body,
            bodyHtml = message.bodyHtml ?: existing.bodyHtml,
            attachments = if (message.attachments.isNotEmpty()) {
                message.attachments.mapIndexed { index, attachment ->
                    AttachmentItem(
                        id = "${message.id}-att-$index",
                        name = attachment.name.ifBlank { "attachment" },
                        sizeLabel = formatSize(attachment.size),
                        mimeType = attachment.type.ifBlank { "application/octet-stream" },
                    )
                }
            } else {
                existingFirst.attachments
            },
        ) ?: ThreadMessage(
            id = message.id,
            senderName = existing.primarySenderName,
            senderEmail = existing.primarySenderEmail,
            senderColorHex = existing.primarySenderColorHex,
            recipientsTo = listOf(existing.accountEmail),
            body = body,
            bodyHtml = message.bodyHtml ?: existing.bodyHtml,
            timestampMillis = existing.updatedAtMillis,
            isStarred = message.isStarred,
            attachments = message.attachments.mapIndexed { index, attachment ->
                AttachmentItem(
                    id = "${message.id}-att-$index",
                    name = attachment.name.ifBlank { "attachment" },
                    sizeLabel = formatSize(attachment.size),
                    mimeType = attachment.type.ifBlank { "application/octet-stream" },
                )
            }
        )
        return existing.copy(
            subject = if (existing.subject == "(no subject)" && message.subject.isNotBlank()) {
                message.subject
            } else {
                existing.subject
            },
            snippet = existing.snippet.ifBlank { message.snippet },
            messages = listOfNotNull(updatedMessage),
            bodyText = body,
            bodyHtml = message.bodyHtml ?: existing.bodyHtml,
            etag = etag,
            bodyLoaded = true,
            isRead = message.isRead || existing.isRead,
            isStarred = message.isStarred,
        )
    }

    /** Maps the Worker's `/api/session` user onto the local account row. */
    fun toAccount(
        user: ApiSessionUser,
        existing: MailAccountEntity?,
        active: Boolean = true,
    ): MailAccountEntity {
        val username = user.username.ifBlank { user.email.substringBefore('@') }
        val email = user.email.ifBlank { "$username@bansify.com" }
        val display = user.name?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ")
                .takeIf { it.isNotBlank() }
            ?: username
        return MailAccountEntity(
            email = email,
            userId = existing?.userId ?: "",
            username = username,
            displayName = display,
            avatarColorHex = existing?.avatarColorHex ?: avatarColor(email),
            storageUsedBytes = existing?.storageUsedBytes ?: 0L,
            storageQuotaBytes = existing?.storageQuotaBytes ?: ApiMailSettings.DEFAULT_STORAGE_QUOTA_BYTES,
            totalMessages = existing?.totalMessages ?: 0,
            starredMessages = existing?.starredMessages ?: 0,
            retentionLimit = existing?.retentionLimit ?: ApiMailSettings.RETENTION_500,
            unreadCount = existing?.unreadCount ?: 0,
            signature = existing?.signature ?: "Sent from Bansify Mail",
            vacationEnabled = existing?.vacationEnabled ?: false,
            vacationMessage = existing?.vacationMessage
                ?: "Thanks for your email. I am currently away and will respond soon.",
            isActive = active,
        )
    }

    /** Maps the Accounts profile onto the local account row. */
    fun toAccount(
        info: GothwadUserInfo,
        existing: MailAccountEntity?,
        active: Boolean = true,
    ): MailAccountEntity {
        val username = info.username?.takeIf { it.isNotBlank() }
            ?: existing?.username
            ?: info.email?.substringBefore('@')
            ?: info.userId.take(8)
        val email = info.email?.takeIf { it.isNotBlank() } ?: existing?.email ?: "$username@bansify.com"
        val display = info.name?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(info.givenName, info.familyName).filter { it.isNotBlank() }.joinToString(" ")
                .takeIf { it.isNotBlank() }
            ?: existing?.displayName
            ?: username
        return MailAccountEntity(
            email = email,
            userId = info.userId,
            username = username,
            displayName = display,
            avatarColorHex = existing?.avatarColorHex ?: avatarColor(email),
            storageUsedBytes = existing?.storageUsedBytes ?: 0L,
            storageQuotaBytes = existing?.storageQuotaBytes ?: ApiMailSettings.DEFAULT_STORAGE_QUOTA_BYTES,
            totalMessages = existing?.totalMessages ?: 0,
            starredMessages = existing?.starredMessages ?: 0,
            retentionLimit = existing?.retentionLimit ?: ApiMailSettings.RETENTION_500,
            unreadCount = existing?.unreadCount ?: 0,
            signature = existing?.signature ?: "Sent from Bansify Mail",
            vacationEnabled = existing?.vacationEnabled ?: false,
            vacationMessage = existing?.vacationMessage
                ?: "Thanks for your email. I am currently away and will respond soon.",
            isActive = active,
        )
    }

    /** Applies the Worker settings + unread counts onto an existing account row. */
    fun withServerStats(
        account: MailAccountEntity,
        settings: ApiMailSettings?,
        unread: Int?,
    ): MailAccountEntity = account.copy(
        storageUsedBytes = settings?.storageUsedBytes ?: account.storageUsedBytes,
        storageQuotaBytes = settings?.storageQuotaBytes?.takeIf { it > 0L } ?: account.storageQuotaBytes,
        totalMessages = settings?.totalMessages ?: account.totalMessages,
        starredMessages = settings?.starredMessages ?: account.starredMessages,
        retentionLimit = settings?.retentionLimit ?: account.retentionLimit,
        unreadCount = unread ?: account.unreadCount,
    )

    /** Converts a `{ name, type, size }` attachment list into UI items. */
    fun toAttachmentItems(messageId: String, attachments: List<com.bansify.mail.data.remote.ApiAttachment>): List<AttachmentItem> =
        attachments.mapIndexed { index, attachment ->
            AttachmentItem(
                id = "$messageId-att-$index",
                name = attachment.name.ifBlank { "attachment" },
                sizeLabel = formatSize(attachment.size),
                mimeType = attachment.type.ifBlank { "application/octet-stream" },
            )
        }

    /** Maps a cached thread back to its UI folder when the server folder is authoritative. */
    fun uiFolderFor(serverFolder: ServerFolder, fallback: MailFolder): MailFolder = when (serverFolder) {
        ServerFolder.INBOX -> when (fallback) {
            MailFolder.PROMOTIONS, MailFolder.SOCIAL, MailFolder.UPDATES, MailFolder.IMPORTANT,
            MailFolder.STARRED, MailFolder.SNOOZED, MailFolder.SPAM -> fallback
            else -> fallback
        }
        ServerFolder.STARRED -> MailFolder.STARRED
        ServerFolder.SENT -> MailFolder.SENT
        ServerFolder.DRAFTS -> MailFolder.DRAFT
        ServerFolder.TRASH -> MailFolder.TRASH
    }

    /** Strips HTML to readable text for the plain-text body fallback. */
    fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Human readable attachment size. */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> "0 KB"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
        else -> String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
