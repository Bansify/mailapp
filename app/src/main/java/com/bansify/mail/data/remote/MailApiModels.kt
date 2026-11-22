package com.bansify.mail.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server folder values accepted by `GET /api/mail/messages?folder=`. */
object ApiFolder {
    const val INBOX = "inbox"
    const val STARRED = "starred"
    const val SENT = "sent"
    const val DRAFTS = "drafts"
    const val TRASH = "trash"
}

@Serializable
data class ApiAttachment(
    val name: String = "",
    val type: String = "application/octet-stream",
    val size: Long = 0L,
)

@Serializable
data class ApiAddress(
    val name: String? = null,
    val email: String = "",
)

/** One message as returned by `/api/mail/messages` and `/api/mail/messages/:id`. */
@Serializable
data class ApiMessage(
    val id: String,
    val folder: String = ApiFolder.INBOX,
    val from: ApiAddress = ApiAddress(),
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val snippet: String = "",
    val receivedAt: String = "",
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val attachments: List<ApiAttachment> = emptyList(),
    val bodyText: String? = null,
    val bodyHtml: String? = null,
)

@Serializable
data class ApiMessageList(
    val messages: List<ApiMessage> = emptyList(),
    val folder: String = ApiFolder.INBOX,
    val nextBefore: String? = null,
)

@Serializable
data class ApiMessageEnvelope(val message: ApiMessage)

@Serializable
data class ApiSessionUser(
    val username: String = "",
    val email: String = "",
    val name: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class ApiLimits(
    val dailySendLimit: Int? = null,
    val sendRemainingToday: Int? = null,
)

@Serializable
data class ApiSession(
    val authenticated: Boolean = false,
    val user: ApiSessionUser = ApiSessionUser(),
    val unread: Map<String, Int> = emptyMap(),
    val limits: ApiLimits = ApiLimits(),
)

@Serializable
data class ApiMailSettings(
    val retentionLimit: Int = RETENTION_500,
    val storageQuotaBytes: Long = DEFAULT_STORAGE_QUOTA_BYTES,
    val storageUsedBytes: Long = 0L,
    val totalMessages: Int = 0,
    val starredMessages: Int = 0,
    val updatedAt: String? = null,
) {
    companion object {
        const val RETENTION_500 = 500
        const val RETENTION_1500 = 1500
        const val RETENTION_ALL = 0

        /** 50 MB fair-use quota used by the Mail Worker by default. */
        const val DEFAULT_STORAGE_QUOTA_BYTES: Long = 52_428_800L

        val VALID_RETENTION_LIMITS: Set<Int> = setOf(RETENTION_500, RETENTION_1500, RETENTION_ALL)
    }
}

@Serializable
data class ApiBatchResult(val ok: Boolean = false, val updated: Int = 0)

@Serializable
data class ApiOkResult(val ok: Boolean = false, val deleted: Int? = null)

@Serializable
data class ApiSendResult(
    val id: String? = null,
    val sent: Boolean = false,
    val saved: Boolean = false,
    val remainingToday: Int? = null,
)

/** Message mutation body for `PATCH /api/mail/messages/:id`. */
@Serializable
data class ApiMessagePatch(
    val isRead: Boolean? = null,
    val isStarred: Boolean? = null,
    val moveToTrash: Boolean? = null,
    val moveToInbox: Boolean? = null,
)

/** Batch mutation body for `POST /api/mail/messages/batch`. */
@Serializable
data class ApiMessageBatch(
    val ids: List<String>,
    val isRead: Boolean? = null,
    val isStarred: Boolean? = null,
    val moveToTrash: Boolean? = null,
    val moveToInbox: Boolean? = null,
    val deleteFromTrash: Boolean? = null,
)

@Serializable
data class ApiDraftBody(
    val id: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
)

@Serializable
data class ApiSendBody(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val draftId: String? = null,
)

@Serializable
data class ApiSettingsPatch(val retentionLimit: Int)

/** A message body plus the ETag that makes re-opening it free. */
data class MailMessageDetail(
    val message: ApiMessage,
    val etag: String?,
    val notModified: Boolean = false,
)

/** Error codes the Worker can return that the UI surfaces verbatim. */
object MailApiErrorCode {
    const val UNAUTHENTICATED = "unauthenticated"
    const val DAILY_LIMIT = "daily_limit"
    const val SENDING_NOT_CONFIGURED = "sending_not_configured"
    const val NOT_FOUND = "not_found"
}

/** Typed failure raised by [MailApiClient]. */
class MailApiException(
    val statusCode: Int,
    val code: String,
    message: String,
    val retryAfterSeconds: Int? = null,
) : java.io.IOException(message) {

    val isUnauthenticated: Boolean get() = statusCode == 401 || code == MailApiErrorCode.UNAUTHENTICATED
    val isForbidden: Boolean get() = statusCode == 403
    val isNotFound: Boolean get() = statusCode == 404 || code == MailApiErrorCode.NOT_FOUND
    val isRateLimited: Boolean get() = statusCode == 429
}

/** Localized, user-facing text for a failed send. */
fun sendFailureMessage(error: Throwable): String = when {
    error is MailApiException && error.isRateLimited ->
        "Daily send limit reached — try again tomorrow"
    error is MailApiException && error.code == MailApiErrorCode.SENDING_NOT_CONFIGURED ->
        "Sending is not configured for this account yet"
    error is MailApiException && error.isUnauthenticated ->
        "Your Bansify Account session expired. Sign in again."
    error is MailApiException && error.isForbidden ->
        "This Bansify Account cannot send mail from Bansify Mail"
    error is java.io.IOException -> "Could not reach Bansify Mail. Check your connection and retry."
    else -> "Could not send the message. Try again."
}

/** Human readable byte label used by the storage meter. */
fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1_048_576.0
    if (mb < 1.0) return "${(bytes / 1024.0).let { if (it < 1) 1L else it.toLong() }} KB"
    if (mb < 1024.0) {
        val rounded = if (mb < 10) String.format("%.1f", mb) else mb.toLong().toString()
        return "$rounded MB"
    }
    val gb = mb / 1024.0
    return "${String.format("%.1f", gb)} GB"
}
