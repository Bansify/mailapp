package com.gothwad.mail.data.repository

import com.gothwad.mail.data.local.MailDao
import com.gothwad.mail.data.model.AppSettingsEntity
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.data.model.RetentionPolicy
import com.gothwad.mail.data.model.ServerFolder
import com.gothwad.mail.data.model.ThreadMessage
import com.gothwad.mail.data.remote.ApiBatchResult
import com.gothwad.mail.data.remote.ApiDraftBody
import com.gothwad.mail.data.remote.ApiMailSettings
import com.gothwad.mail.data.remote.ApiMessageBatch
import com.gothwad.mail.data.remote.ApiMessagePatch
import com.gothwad.mail.data.remote.ApiSendBody
import com.gothwad.mail.data.remote.ApiSendResult
import com.gothwad.mail.data.remote.ApiSession
import com.gothwad.mail.data.remote.MailApiClient
import com.gothwad.mail.data.remote.ApiMessage
import com.gothwad.mail.data.remote.MailMessageDetail
import com.gothwad.mail.data.remote.MailApiException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Offline-first bridge between the Gothwad Mail Worker API and the per-account Room cache.
 *
 * Every operation follows the same shape: update Room optimistically so the UI reacts instantly,
 * then call the Worker API and reconcile. All reads are scoped by the active account's email, so
 * Account A's mail can never appear under Account B.
 */
class MailRepository(
    private val dao: MailDao,
    private val api: MailApiClient,
) {

    // --- Observable state ----------------------------------------------------

    fun observeThreadsForAccount(accountEmail: String): Flow<List<EmailThreadEntity>> =
        dao.observeThreadsForAccount(accountEmail)

    fun observeAllThreads(): Flow<List<EmailThreadEntity>> = dao.observeAllThreads()

    val accounts: Flow<List<MailAccountEntity>> = dao.observeAccounts()

    fun observeActiveAccount(): Flow<MailAccountEntity?> = dao.observeActiveAccount()

    val settings: Flow<AppSettingsEntity> = dao.observeSettings()
        .map { it ?: AppSettingsEntity() }

    fun observeThread(threadId: String): Flow<EmailThreadEntity?> = dao.observeThreadById(threadId)

    /** One-shot read of a cached thread, used by actions that must not wait for a flow. */
    suspend fun getThread(threadId: String): EmailThreadEntity? = dao.getThreadById(threadId)

    fun observeThreadForAccount(accountEmail: String, threadId: String): Flow<EmailThreadEntity?> =
        dao.observeThreadForAccount(accountEmail, threadId)

    // --- Account plumbing ----------------------------------------------------

    /** Persists the settings row on first launch. No demo content is ever written. */
    suspend fun ensureInitialized() {
        if (dao.getSettings() == null) {
            dao.upsertSettings(AppSettingsEntity())
        }
    }

    suspend fun switchAccount(email: String) {
        dao.setActiveAccount(email)
    }

    suspend fun upsertAccount(account: MailAccountEntity) {
        dao.insertAccount(account)
    }

    suspend fun updateAccount(account: MailAccountEntity) {
        dao.updateAccount(account)
    }

    /** Removes the account row and every cached message it owned. */
    suspend fun removeAccount(email: String) {
        dao.deleteAllForAccount(email)
        dao.deleteAccount(email)
        val remaining = dao.getAccounts()
        if (remaining.isNotEmpty() && remaining.none { it.isActive }) {
            dao.setActiveAccount(remaining.first().email)
        }
    }

    suspend fun clearAllAccounts() {
        dao.deleteAllAccounts()
    }

    suspend fun updateSettings(settings: AppSettingsEntity) {
        dao.upsertSettings(settings)
    }

    suspend fun activeAccount(): MailAccountEntity? = dao.getActiveAccount()

    suspend fun accountsSnapshot(): List<MailAccountEntity> = dao.getAccounts()

    // --- Sync ----------------------------------------------------------------

    /**
     * Refreshes the session, mailbox settings and the current folder in one pass.
     *
     * The folder mapping follows the Worker contract: every inbox category and "all mail" read
     * `folder=inbox`, starred reads `folder=starred`, and so on.
     */
    suspend fun refreshCurrentFolder(
        account: MailAccountEntity,
        folder: MailFolder,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): MailboxSnapshot {
        val serverFolder = ServerFolder.forMailFolder(folder)
        val page = api.messages(folder = serverFolder.wireValue, limit = limit)
        val existing = dao.getThreadsForAccount(account.email).associateBy { it.serverId }

        // Replace only the local slice that mirrors this server folder. Starred is a cross-folder
        // view, so it upserts without ever deleting rows.
        mirroredLocalFolders(serverFolder).forEach { local ->
            dao.deleteAllInFolderForAccount(local, account.email)
        }

        val merged = page.messages.map { message ->
            val previous = existing[message.id]
            val localFolder = localFolderFor(message.folder, previous?.folder)
            val mapped = MailMappers.toEntity(
                message = message,
                accountEmail = account.email,
                uiFolder = localFolder,
                existing = previous,
            )
            if (previous != null) {
                // The list endpoint omits bodies; keep whatever was already downloaded.
                mapped.copy(
                    bodyText = previous.bodyText,
                    bodyHtml = previous.bodyHtml,
                    etag = previous.etag,
                    bodyLoaded = previous.bodyLoaded,
                    isImportant = previous.isImportant,
                    labels = previous.labels,
                )
            } else {
                mapped
            }
        }
        dao.insertThreads(merged)

        val settings = runCatching { api.mailSettings() }.getOrNull()
        val session = runCatching { api.session() }.getOrNull()
        val refreshed = MailMappers.withServerStats(account, settings, session?.unread?.get(account.username))
        dao.updateAccount(refreshed)
        return MailboxSnapshot(
            account = refreshed,
            session = session,
            messages = page.messages,
            settings = settings,
            nextBefore = page.nextBefore,
        )
    }

    /** Loads the session and settings without touching messages. */
    suspend fun refreshAccountMetadata(account: MailAccountEntity): MailboxSnapshot {
        val session = api.session()
        val settings = runCatching { api.mailSettings() }.getOrNull()
        val refreshed = MailMappers.withServerStats(
            MailMappers.toAccount(session.user, account, account.isActive),
            settings,
            session.unread[account.username],
        )
        dao.insertAccount(refreshed.copy(retentionLimit = account.retentionLimit))
        return MailboxSnapshot(refreshed, session, emptyList(), settings, null)
    }

    /** Server-side search, merged into the local cache. */
    suspend fun search(
        account: MailAccountEntity,
        query: String,
        folder: MailFolder = MailFolder.ALL_MAIL,
        unreadOnly: Boolean = false,
        starredOnly: Boolean = false,
        hasAttachment: Boolean = false,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): List<EmailThreadEntity> {
        val serverFolder = ServerFolder.forMailFolder(folder)
        val result = api.messages(
            folder = serverFolder.wireValue,
            limit = limit,
            query = query,
            unreadOnly = unreadOnly,
            starredOnly = starredOnly,
            hasAttachment = hasAttachment,
        )
        val existing = dao.getThreadsForAccount(account.email).associateBy { it.serverId }
        val entities = result.messages.map { message ->
            val previous = existing[message.id]
            val mapped = MailMappers.toEntity(message, account.email, folder, previous)
            if (previous != null) {
                mapped.copy(
                    bodyText = previous.bodyText,
                    bodyHtml = previous.bodyHtml,
                    etag = previous.etag,
                    bodyLoaded = previous.bodyLoaded,
                )
            } else {
                mapped
            }
        }
        // Search results are cached so opening one is instant, but they do not replace a folder.
        dao.insertThreads(entities)
        return entities
    }

    // --- Message detail ------------------------------------------------------

    /**
     * Opens a cached thread: marks it read (optimistically, then on the server) and downloads the
     * body with `If-None-Match`, reusing the cached body on `304 Not Modified`.
     */
    suspend fun openThread(
        account: MailAccountEntity,
        thread: EmailThreadEntity,
    ): EmailThreadEntity {
        if (!thread.isRead) {
            dao.updateReadStatus(account.email, listOf(thread.serverId), true)
            runCatching { api.patchMessage(thread.serverId, ApiMessagePatch(isRead = true)) }
        }
        val cached = dao.getThreadById(thread.id) ?: thread
        if (cached.bodyLoaded && !cached.bodyText.isNullOrBlank()) {
            return cached
        }
        return try {
            val detail: MailMessageDetail = api.message(thread.serverId, cached.etag)
            if (detail.notModified) {
                dao.updateEtag(cached.id, detail.etag)
                cached.copy(etag = detail.etag ?: cached.etag, bodyLoaded = true)
            } else {
                val withBody = MailMappers.withBody(cached, detail.message, detail.etag)
                dao.updateBody(
                    accountEmail = withBody.accountEmail,
                    serverId = withBody.serverId,
                    bodyText = withBody.bodyText,
                    bodyHtml = withBody.bodyHtml,
                    etag = withBody.etag,
                    snippet = withBody.snippet,
                )
                dao.insertThread(withBody)
                withBody
            }
        } catch (error: MailApiException) {
            if (error.isNotFound) {
                dao.deleteThreadsByServerIds(account.email, listOf(thread.serverId))
            }
            throw error
        }
    }

    // --- Mutations -----------------------------------------------------------

    suspend fun markRead(account: MailAccountEntity, threads: List<EmailThreadEntity>, isRead: Boolean) {
        if (threads.isEmpty()) return
        val serverIds = threads.map { it.serverId }
        dao.updateReadStatus(account.email, serverIds, isRead)
        if (serverIds.size == 1) {
            applyToServer(serverIds) { api.patchMessage(it, ApiMessagePatch(isRead = isRead)) }
        } else {
            api.batchMessages(ApiMessageBatch(ids = serverIds, isRead = isRead))
        }
    }

    /** Star / unstar one thread: optimistic Room update, then `PATCH ... { isStarred }`. */
    suspend fun toggleStar(account: MailAccountEntity, thread: EmailThreadEntity): Boolean {
        val next = !thread.isStarred
        dao.updateStarred(thread.id, next)
        runCatching { api.patchMessage(thread.serverId, ApiMessagePatch(isStarred = next)) }
            .onFailure {
                // Roll back the optimistic change so the UI never lies about server state.
                dao.updateStarred(thread.id, thread.isStarred)
            }
        return next
    }

    suspend fun toggleImportant(account: MailAccountEntity, thread: EmailThreadEntity): Boolean {
        val next = !thread.isImportant
        dao.updateImportant(thread.id, next)
        return next
    }

    /** Moves threads to Trash: optimistic Room move + server `moveToTrash` (batch when > 1). */
    suspend fun moveToTrash(account: MailAccountEntity, threads: List<EmailThreadEntity>) {
        if (threads.isEmpty()) return
        val serverIds = threads.map { it.serverId }
        dao.moveThreadsToFolder(account.email, serverIds, MailFolder.TRASH, isArchived = false)
        if (serverIds.size == 1) {
            applyToServer(serverIds) { api.patchMessage(it, ApiMessagePatch(moveToTrash = true)) }
        } else {
            api.batchMessages(ApiMessageBatch(ids = serverIds, moveToTrash = true))
        }
    }

    /** Undo swipe: restores Room state and calls `PATCH ... { moveToInbox: true }`. */
    suspend fun restoreFromTrash(account: MailAccountEntity, threads: List<EmailThreadEntity>) {
        if (threads.isEmpty()) return
        val serverIds = threads.map { it.serverId }
        threads.forEach { thread ->
            dao.setFolder(thread.id, thread.previousFolder, thread.previousFolder)
        }
        if (serverIds.size == 1) {
            applyToServer(serverIds) { api.patchMessage(it, ApiMessagePatch(moveToInbox = true)) }
        } else {
            api.batchMessages(ApiMessageBatch(ids = serverIds, moveToInbox = true))
        }
    }

    /** Permanent delete of one trashed message (`DELETE /api/mail/messages/:id`). */
    suspend fun deleteForever(account: MailAccountEntity, threads: List<EmailThreadEntity>) {
        if (threads.isEmpty()) return
        threads.forEach { thread -> runCatching { api.deleteMessage(thread.serverId) } }
        dao.deleteThreadsByServerIds(account.email, threads.map { it.serverId })
    }

    /** `DELETE /api/mail/trash`, then drop the cached trash slice. */
    suspend fun emptyTrash(account: MailAccountEntity): Int {
        val result = runCatching { api.emptyTrash() }.getOrElse { throw it }
        val deleted = result.deleted ?: 0
        dao.deleteAllInFolderForAccount(MailFolder.TRASH, account.email)
        return deleted
    }

    /** Batch mutation used by multi-select actions. */
    suspend fun batch(
        account: MailAccountEntity,
        threads: List<EmailThreadEntity>,
        isRead: Boolean? = null,
        isStarred: Boolean? = null,
        moveToTrash: Boolean? = null,
        moveToInbox: Boolean? = null,
        deleteFromTrash: Boolean? = null,
    ): ApiBatchResult {
        if (threads.isEmpty()) return ApiBatchResult(ok = true, updated = 0)
        val serverIds = threads.map { it.serverId }
        if (isRead != null) dao.updateReadStatus(account.email, serverIds, isRead)
        if (moveToTrash == true) dao.moveThreadsToFolder(account.email, serverIds, MailFolder.TRASH, false)
        if (moveToInbox == true) {
            threads.forEach { dao.setFolder(it.id, it.previousFolder, it.previousFolder) }
        }
        if (deleteFromTrash == true) dao.deleteThreadsByServerIds(account.email, serverIds)
        return api.batchMessages(
            ApiMessageBatch(
                ids = serverIds,
                isRead = isRead,
                isStarred = isStarred,
                moveToTrash = moveToTrash,
                moveToInbox = moveToInbox,
                deleteFromTrash = deleteFromTrash,
            ),
        )
    }

    // --- Compose, drafts & sending -------------------------------------------

    /**
     * Saves a draft on the server and caches it. [serverDraftId] is the Worker draft UUID, which is
     * stored in Room so the next save updates the same draft instead of creating a new one.
     */
    suspend fun saveDraft(
        account: MailAccountEntity,
        serverDraftId: String?,
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String,
    ): EmailThreadEntity {
        val saved = api.saveDraft(
            ApiDraftBody(
                id = serverDraftId,
                to = to,
                cc = cc,
                bcc = emptyList(),
                subject = subject,
                body = body,
            ),
        )
        val previous = dao.getThreadByServerId(account.email, saved.id)
        val entity = MailMappers.toEntity(saved, account.email, MailFolder.DRAFT, previous).copy(
            bodyText = body,
            bodyLoaded = true,
            snippet = "Draft: " + body.trim().replace("\n", " ").take(100),
        )
        dao.insertThread(entity)
        return entity
    }

    suspend fun deleteDraft(account: MailAccountEntity, thread: EmailThreadEntity) {
        dao.deleteThreadsByIds(listOf(thread.id))
        runCatching { api.deleteDraft(thread.serverId) }
    }

    /**
     * Sends a message through the Worker. [draftId] is the server draft that is consumed; on
     * success the cached draft is dropped and the sent slice is refreshed.
     */
    suspend fun send(
        account: MailAccountEntity,
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String,
        draftId: String?,
    ): ApiSendResult {
        val result = api.send(
            ApiSendBody(
                to = to,
                cc = cc,
                bcc = emptyList(),
                subject = subject,
                body = body,
                draftId = draftId,
            ),
        )
        if (draftId != null) {
            dao.getThreadByServerId(account.email, draftId)?.let { dao.deleteThreadsByIds(listOf(it.id)) }
        }
        runCatching { refreshCurrentFolder(account, MailFolder.SENT) }
        return result
    }

    // --- Settings ------------------------------------------------------------

    /** `PATCH /api/mail/settings` with the selected retention policy. */
    suspend fun updateRetention(account: MailAccountEntity, policy: RetentionPolicy): ApiMailSettings {
        val settings = api.updateMailSettings(policy.serverValue)
        dao.updateAccount(account.copy(retentionLimit = policy.serverValue))
        return settings
    }

    // --- Helpers -------------------------------------------------------------

    /**
     * Runs [mutate] for each id, isolating failures so one rejected message cannot lose the rest of
     * a multi-select operation. The first failure is rethrown after every id has been attempted.
     */
    private suspend fun applyToServer(serverIds: List<String>, mutate: suspend (String) -> Unit) {
        var failure: Throwable? = null
        serverIds.forEach { id ->
            runCatching { mutate(id) }.onFailure { error -> if (failure == null) failure = error }
        }
        failure?.let { throw it }
    }

    /** Local cache folders that hold the messages of one server folder. */
    private fun mirroredLocalFolders(serverFolder: ServerFolder): Set<MailFolder> = when (serverFolder) {
        ServerFolder.INBOX -> setOf(MailFolder.PRIMARY)
        // Starred is a view over every folder: upsert only, never delete.
        ServerFolder.STARRED -> emptySet()
        ServerFolder.SENT -> setOf(MailFolder.SENT, MailFolder.SCHEDULED)
        ServerFolder.DRAFTS -> setOf(MailFolder.DRAFT)
        ServerFolder.TRASH -> setOf(MailFolder.TRASH)
    }

    /** Cache folder for a message, derived from the server's authoritative folder value. */
    private fun localFolderFor(serverFolder: String?, fallback: MailFolder?): MailFolder =
        when (ServerFolder.fromWire(serverFolder)) {
            ServerFolder.INBOX -> MailFolder.PRIMARY
            ServerFolder.STARRED -> fallback ?: MailFolder.PRIMARY
            ServerFolder.SENT -> MailFolder.SENT
            ServerFolder.DRAFTS -> MailFolder.DRAFT
            ServerFolder.TRASH -> MailFolder.TRASH
        }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}

/** Everything one folder refresh produced, so callers can react without extra API calls. */
data class MailboxSnapshot(
    val account: MailAccountEntity,
    val session: ApiSession?,
    val messages: List<ApiMessage>,
    val settings: ApiMailSettings?,
    val nextBefore: String?,
)
