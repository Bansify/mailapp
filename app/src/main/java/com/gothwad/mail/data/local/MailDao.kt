package com.gothwad.mail.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gothwad.mail.data.model.AppSettingsEntity
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.data.model.MailFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface MailDao {

    @Query("SELECT * FROM email_threads ORDER BY updatedAtMillis DESC")
    fun observeAllThreads(): Flow<List<EmailThreadEntity>>

    @Query("SELECT * FROM email_threads WHERE id = :threadId LIMIT 1")
    fun observeThreadById(threadId: Long): Flow<EmailThreadEntity?>

    @Query("SELECT * FROM email_threads WHERE id = :threadId LIMIT 1")
    suspend fun getThreadById(threadId: Long): EmailThreadEntity?

    @Query("SELECT COUNT(*) FROM email_threads")
    suspend fun getThreadCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThread(thread: EmailThreadEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThreads(threads: List<EmailThreadEntity>)

    @Update
    suspend fun updateThread(thread: EmailThreadEntity)

    @Query("UPDATE email_threads SET isRead = :isRead WHERE id IN (:ids)")
    suspend fun updateReadStatus(ids: List<Long>, isRead: Boolean)

    @Query("UPDATE email_threads SET isStarred = :isStarred WHERE id = :id")
    suspend fun updateStarred(id: Long, isStarred: Boolean)

    @Query("UPDATE email_threads SET isImportant = :isImportant WHERE id = :id")
    suspend fun updateImportant(id: Long, isImportant: Boolean)

    @Query("UPDATE email_threads SET folder = :targetFolder, isArchived = :isArchived WHERE id IN (:ids)")
    suspend fun moveThreadsToFolder(ids: List<Long>, targetFolder: MailFolder, isArchived: Boolean)

    @Query("DELETE FROM email_threads WHERE id IN (:ids)")
    suspend fun deleteThreadsByIds(ids: List<Long>)

    @Query("DELETE FROM email_threads WHERE folder = :folder AND accountEmail = :accountEmail")
    suspend fun deleteAllInFolderForAccount(folder: MailFolder, accountEmail: String)

    // Accounts
    @Query("SELECT * FROM accounts")
    fun observeAccounts(): Flow<List<MailAccountEntity>>

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

    // Settings
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AppSettingsEntity)
}
