package com.bansify.mail.data.repository

import com.bansify.mail.data.local.MailDao
import com.bansify.mail.data.model.AppSettingsEntity
import com.bansify.mail.data.model.EmailThreadEntity
import com.bansify.mail.data.model.MailAccountEntity
import com.bansify.mail.data.model.MailFolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [MailDao] with the same account scoping as the Room implementation, so repository
 * behaviour can be tested on the JVM without an Android runtime.
 */
class FakeMailDao : MailDao {

    val threads = MutableStateFlow<List<EmailThreadEntity>>(emptyList())
    val accountList = MutableStateFlow<List<MailAccountEntity>>(emptyList())
    val settingsState = MutableStateFlow<AppSettingsEntity?>(null)

    override fun observeThreadsForAccount(accountEmail: String): Flow<List<EmailThreadEntity>> =
        threads.map { list ->
            list.filter { it.accountEmail.equals(accountEmail, ignoreCase = true) }
                .sortedByDescending { it.updatedAtMillis }
        }

    override fun observeAllThreads(): Flow<List<EmailThreadEntity>> =
        threads.map { list -> list.sortedByDescending { it.updatedAtMillis } }

    override fun observeThreadForAccount(accountEmail: String, threadId: String): Flow<EmailThreadEntity?> =
        threads.map { list ->
            list.firstOrNull { it.id == threadId && it.accountEmail.equals(accountEmail, ignoreCase = true) }
        }

    override fun observeThreadById(threadId: String): Flow<EmailThreadEntity?> =
        threads.map { list -> list.firstOrNull { it.id == threadId } }

    override suspend fun getThreadById(threadId: String): EmailThreadEntity? =
        threads.value.firstOrNull { it.id == threadId }

    override suspend fun getThreadByServerId(accountEmail: String, serverId: String): EmailThreadEntity? =
        threads.value.firstOrNull { it.serverId == serverId && it.accountEmail.equals(accountEmail, true) }

    override suspend fun getThreadsForAccount(accountEmail: String): List<EmailThreadEntity> =
        threads.value.filter { it.accountEmail.equals(accountEmail, ignoreCase = true) }

    override suspend fun getThreadCount(accountEmail: String): Int =
        threads.value.count { it.accountEmail.equals(accountEmail, ignoreCase = true) }

    override suspend fun getThreadCountInFolder(accountEmail: String, folder: MailFolder): Int =
        threads.value.count { it.accountEmail.equals(accountEmail, true) && it.folder == folder }

    override suspend fun insertThread(thread: EmailThreadEntity): Long {
        threads.value = threads.value.filterNot { it.id == thread.id } + thread
        return thread.id.hashCode().toLong()
    }

    override suspend fun insertThreads(threads: List<EmailThreadEntity>) {
        if (threads.isEmpty()) return
        val incoming = threads.associateBy { it.id }
        this.threads.value = this.threads.value.filterNot { it.id in incoming.keys } + threads
    }

    override suspend fun updateThread(thread: EmailThreadEntity) {
        threads.value = threads.value.map { if (it.id == thread.id) thread else it }
    }

    override suspend fun updateReadStatus(accountEmail: String, serverIds: List<String>, isRead: Boolean) {
        threads.value = threads.value.map {
            if (it.accountEmail.equals(accountEmail, true) && it.serverId in serverIds) it.copy(isRead = isRead) else it
        }
    }

    override suspend fun updateStarred(id: String, isStarred: Boolean) {
        threads.value = threads.value.map { if (it.id == id) it.copy(isStarred = isStarred) else it }
    }

    override suspend fun updateImportant(id: String, isImportant: Boolean) {
        threads.value = threads.value.map { if (it.id == id) it.copy(isImportant = isImportant) else it }
    }

    override suspend fun moveThreadsToFolder(
        accountEmail: String,
        serverIds: List<String>,
        targetFolder: MailFolder,
        isArchived: Boolean,
    ) {
        threads.value = threads.value.map {
            if (it.accountEmail.equals(accountEmail, true) && it.serverId in serverIds) {
                it.copy(previousFolder = it.folder, folder = targetFolder, isArchived = isArchived)
            } else {
                it
            }
        }
    }

    override suspend fun setFolder(id: String, folder: MailFolder, previousFolder: MailFolder) {
        threads.value = threads.value.map {
            if (it.id == id) it.copy(folder = folder, previousFolder = previousFolder) else it
        }
    }

    override suspend fun updateBody(
        accountEmail: String,
        serverId: String,
        bodyText: String,
        bodyHtml: String?,
        etag: String?,
        snippet: String,
    ) {
        threads.value = threads.value.map {
            if (it.accountEmail.equals(accountEmail, true) && it.serverId == serverId) {
                it.copy(bodyText = bodyText, bodyHtml = bodyHtml, etag = etag, bodyLoaded = true, snippet = snippet)
            } else {
                it
            }
        }
    }

    override suspend fun updateEtag(id: String, etag: String?) {
        threads.value = threads.value.map { if (it.id == id) it.copy(etag = etag) else it }
    }

    override suspend fun deleteThreadsByServerIds(accountEmail: String, serverIds: List<String>) {
        threads.value = threads.value.filterNot {
            it.accountEmail.equals(accountEmail, true) && it.serverId in serverIds
        }
    }

    override suspend fun deleteThreadsByIds(ids: List<String>) {
        threads.value = threads.value.filterNot { it.id in ids }
    }

    override suspend fun deleteAllInFolderForAccount(folder: MailFolder, accountEmail: String) {
        threads.value = threads.value.filterNot {
            it.folder == folder && it.accountEmail.equals(accountEmail, ignoreCase = true)
        }
    }

    override suspend fun deleteAllForAccount(accountEmail: String) {
        threads.value = threads.value.filterNot { it.accountEmail.equals(accountEmail, ignoreCase = true) }
    }

    override fun observeAccounts(): Flow<List<MailAccountEntity>> = accountList

    override fun observeActiveAccount(): Flow<MailAccountEntity?> =
        accountList.map { list -> list.firstOrNull { it.isActive } ?: list.firstOrNull() }

    override suspend fun getAccounts(): List<MailAccountEntity> =
        accountList.value.sortedWith(compareByDescending<MailAccountEntity> { it.isActive }.thenBy { it.email })

    override suspend fun getAccount(email: String): MailAccountEntity? =
        accountList.value.firstOrNull { it.email.equals(email, ignoreCase = true) }

    override suspend fun getActiveAccount(): MailAccountEntity? =
        accountList.value.firstOrNull { it.isActive } ?: accountList.value.firstOrNull()

    override suspend fun getAccountCount(): Int = accountList.value.size

    override suspend fun insertAccount(account: MailAccountEntity) {
        accountList.value = accountList.value.filterNot { it.email == account.email } +
            account.copy(email = account.email.lowercase())
    }

    override suspend fun insertAccounts(accounts: List<MailAccountEntity>) {
        accounts.forEach { insertAccount(it) }
    }

    override suspend fun updateAccount(account: MailAccountEntity) {
        accountList.value = accountList.value.map {
            if (it.email.equals(account.email, ignoreCase = true)) account else it
        }
    }

    override suspend fun setActiveAccount(email: String) {
        accountList.value = accountList.value.map { it.copy(isActive = it.email.equals(email, true)) }
    }

    override suspend fun deleteAccount(email: String) {
        accountList.value = accountList.value.filterNot { it.email.equals(email, true) }
    }

    override suspend fun deleteAllAccounts() {
        accountList.value = emptyList()
    }

    override fun observeSettings(): Flow<AppSettingsEntity?> = settingsState

    override suspend fun getSettings(): AppSettingsEntity? = settingsState.value

    override suspend fun upsertSettings(settings: AppSettingsEntity) {
        settingsState.value = settings
    }
}
