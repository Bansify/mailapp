package com.bansify.mail.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bansify.mail.data.model.AppSettingsEntity
import com.bansify.mail.data.model.EmailThreadEntity
import com.bansify.mail.data.model.MailAccountEntity
import com.bansify.mail.data.model.MailFolder
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the per-account mail cache.
 *
 * Every read and every delete is scoped by `accountEmail`, which is what makes account switching
 * instant and leak free.
 */
@Dao
interface MailDao {

    // --- Threads -------------------------------------------------------------

    @Query("SELECT * FROM email_threads WHERE accountEmail = :accountEmail ORDER BY updatedAtMillis DESC")
    fun observeThreadsForAccount(accountEmail: String): Flow<List<EmailThreadEntity>>

    @Query("SELECT * FROM email_threads ORDER BY updatedAtMillis DESC")
    fun observeAllThreads(): Flow<List<EmailThreadEntity>>

    @Query("SELECT * FROM email_threads WHERE accountEmail = :accountEmail AND id = :threadId LIMIT 1")
    fun observeThreadForAccount(accountEmail: String, threadId: String): Flow<EmailThreadEntity?>

    @Query("SELECT * FROM email_threads WHERE id = :threadId LIMIT 1")
    fun observeThreadById(threadId: String): Flow<EmailThreadEntity?>

    @Query("SELECT * FROM email_threads WHERE id = :threadId LIMIT 1")
    suspend fun getThreadById(threadId: String): EmailThreadEntity?

    @Query("SELECT * FROM email_threads WHERE accountEmail = :accountEmail AND serverId = :serverId LIMIT 1")
    suspend fun getThreadByServerId(accountEmail: String, serverId: String): EmailThreadEntity?

    @Query("SELECT * FROM email_threads WHERE accountEmail = :accountEmail")
    suspend fun getThreadsForAccount(accountEmail: String): List<EmailThreadEntity>

    @Query("SELECT COUNT(*) FROM email_threads WHERE accountEmail = :accountEmail")
    suspend fun getThreadCount(accountEmail: String): Int

    @Query("SELECT COUNT(*) FROM email_threads WHERE accountEmail = :accountEmail AND folder = :folder")
    suspend fun getThreadCountInFolder(accountEmail: String, folder: MailFolder): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThread(thread: EmailThreadEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThreads(threads: List<EmailThreadEntity>)

    @Update
    suspend fun updateThread(thread: EmailThreadEntity)

    @Query("UPDATE email_threads SET isRead = :isRead WHERE accountEmail = :accountEmail AND serverId IN (:serverIds)")
    suspend fun updateReadStatus(accountEmail: String, serverIds: List<String>, isRead: Boolean)

    @Query("UPDATE email_threads SET isStarred = :isStarred WHERE id = :id")
    suspend fun updateStarred(id: String, isStarred: Boolean)

    @Query("UPDATE email_threads SET isImportant = :isImportant WHERE id = :id")
    suspend fun updateImportant(id: String, isImportant: Boolean)

    @Query(
        "UPDATE email_threads SET folder = :targetFolder, isArchived = :isArchived " +
            "WHERE accountEmail = :accountEmail AND serverId IN (:serverIds)"
    )
    suspend fun moveThreadsToFolder(
        accountEmail: String,
        serverIds: List<String>,
        targetFolder: MailFolder,
        isArchived: Boolean,
    )

    @Query("UPDATE email_threads SET folder = :folder, previousFolder = :previousFolder WHERE id = :id")
    suspend fun setFolder(id: String, folder: MailFolder, previousFolder: MailFolder)

    @Query(
        "UPDATE email_threads SET bodyText = :bodyText, bodyHtml = :bodyHtml, etag = :etag, " +
            "bodyLoaded = 1, snippet = :snippet WHERE accountEmail = :accountEmail AND serverId = :serverId"
    )
    suspend fun updateBody(
        accountEmail: String,
        serverId: String,
        bodyText: String,
        bodyHtml: String?,
        etag: String?,
        snippet: String,
    )

    @Query("UPDATE email_threads SET etag = :etag WHERE id = :id")
    suspend fun updateEtag(id: String, etag: String?)

    @Query("DELETE FROM email_threads WHERE accountEmail = :accountEmail AND serverId IN (:serverIds)")
    suspend fun deleteThreadsByServerIds(accountEmail: String, serverIds: List<String>)

    @Query("DELETE FROM email_threads WHERE id IN (:ids)")
    suspend fun deleteThreadsByIds(ids: List<String>)

    @Query("DELETE FROM email_threads WHERE folder = :folder AND accountEmail = :accountEmail")
    suspend fun deleteAllInFolderForAccount(folder: MailFolder, accountEmail: String)

    /** Removes every cached message of one account; used when the account is signed out. */
    @Query("DELETE FROM email_threads WHERE accountEmail = :accountEmail")
    suspend fun deleteAllForAccount(accountEmail: String)

    // --- Accounts ------------------------------------------------------------

    @Query("SELECT * FROM accounts")
    fun observeAccounts(): Flow<List<MailAccountEntity>>

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    fun observeActiveAccount(): Flow<MailAccountEntity?>

    @Query("SELECT * FROM accounts ORDER BY isActive DESC, email ASC")
    suspend fun getAccounts(): List<MailAccountEntity>

    @Query("SELECT * FROM accounts WHERE email = :email LIMIT 1")
    suspend fun getAccount(email: String): MailAccountEntity?

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAccount(): MailAccountEntity?

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun getAccountCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: MailAccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<MailAccountEntity>)

    @Update
    suspend fun updateAccount(account: MailAccountEntity)

    @Query("UPDATE accounts SET isActive = (email = :email)")
    suspend fun setActiveAccount(email: String)

    @Query("DELETE FROM accounts WHERE email = :email")
    suspend fun deleteAccount(email: String)

    @Query("DELETE FROM accounts")
    suspend fun deleteAllAccounts()

    // --- Settings ------------------------------------------------------------

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AppSettingsEntity)
}
