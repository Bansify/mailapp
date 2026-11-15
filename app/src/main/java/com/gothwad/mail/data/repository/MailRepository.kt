package com.gothwad.mail.data.repository

import com.gothwad.mail.data.local.MailDao
import com.gothwad.mail.data.local.SeedData
import com.gothwad.mail.data.model.AppSettingsEntity
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.data.model.ThreadMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class MailRepository(private val dao: MailDao) {

    val allThreads: Flow<List<EmailThreadEntity>> = dao.observeAllThreads()
    val accounts: Flow<List<MailAccountEntity>> = dao.observeAccounts()
    val settings: Flow<AppSettingsEntity> = dao.observeSettings().map { it ?: SeedData.initialSettings() }

    fun observeThread(threadId: Long): Flow<EmailThreadEntity?> = dao.observeThreadById(threadId)

    suspend fun ensureSeeded() {
        if (dao.getAccountCount() == 0) {
            dao.insertAccounts(SeedData.initialAccounts())
        }
        if (dao.getThreadCount() == 0) {
            dao.insertThreads(SeedData.initialThreads())
        }
        dao.upsertSettings(SeedData.initialSettings())
    }

    suspend fun switchAccount(email: String) {
        dao.setActiveAccount(email)
    }

    suspend fun addAccount(displayName: String, email: String, colorHex: Long) {
        val newAccount = MailAccountEntity(
            email = email.trim().lowercase(),
            displayName = displayName.trim(),
            avatarColorHex = colorHex,
            usedStorageGb = 0.2f,
            totalStorageGb = 15.0f,
            signature = "Sent from MailApp",
            isActive = true
        )
        dao.insertAccount(newAccount)
        dao.setActiveAccount(newAccount.email)
    }

    suspend fun updateAccount(account: MailAccountEntity) {
        dao.updateAccount(account)
    }

    suspend fun updateSettings(settings: AppSettingsEntity) {
        dao.upsertSettings(settings)
    }

    suspend fun markThreadsRead(ids: List<Long>, isRead: Boolean) {
        if (ids.isEmpty()) return
        dao.updateReadStatus(ids, isRead)
    }

    suspend fun toggleStar(thread: EmailThreadEntity) {
        dao.updateStarred(thread.id, !thread.isStarred)
    }

    suspend fun toggleImportant(thread: EmailThreadEntity) {
        dao.updateImportant(thread.id, !thread.isImportant)
    }

    suspend fun archiveThreads(threads: List<EmailThreadEntity>) {
        threads.forEach { thread ->
            dao.updateThread(
                thread.copy(
                    previousFolder = thread.folder,
                    folder = MailFolder.ALL_MAIL,
                    isArchived = true,
                    updatedAtMillis = thread.updatedAtMillis
                )
            )
        }
    }

    suspend fun moveThreadsToTrash(threads: List<EmailThreadEntity>) {
        threads.forEach { thread ->
            dao.updateThread(
                thread.copy(
                    previousFolder = if (thread.folder != MailFolder.TRASH) thread.folder else thread.previousFolder,
                    folder = MailFolder.TRASH,
                    isArchived = false
                )
            )
        }
    }

    suspend fun moveThreadsToSpam(threads: List<EmailThreadEntity>) {
        threads.forEach { thread ->
            dao.updateThread(
                thread.copy(
                    previousFolder = thread.folder,
                    folder = MailFolder.SPAM,
                    isArchived = false
                )
            )
        }
    }

    suspend fun restoreThreads(threads: List<EmailThreadEntity>) {
        threads.forEach { thread ->
            val target = if (thread.previousFolder == MailFolder.TRASH || thread.previousFolder == MailFolder.SPAM) {
                MailFolder.PRIMARY
            } else {
                thread.previousFolder
            }
            dao.updateThread(
                thread.copy(
                    folder = target,
                    isArchived = false
                )
            )
        }
    }

    suspend fun moveThreadToFolder(thread: EmailThreadEntity, targetFolder: MailFolder) {
        dao.updateThread(
            thread.copy(
                previousFolder = thread.folder,
                folder = targetFolder,
                isArchived = targetFolder == MailFolder.ALL_MAIL
            )
        )
    }

    suspend fun snoozeThread(thread: EmailThreadEntity, untilLabel: String) {
        dao.updateThread(
            thread.copy(
                previousFolder = thread.folder,
                folder = MailFolder.SNOOZED,
                snoozedUntilLabel = untilLabel
            )
        )
    }

    suspend fun updateThreadLabels(thread: EmailThreadEntity, labels: List<String>) {
        dao.updateThread(thread.copy(labels = labels.distinct()))
    }

    suspend fun deleteForever(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao.deleteThreadsByIds(ids)
    }

    suspend fun emptyFolder(folder: MailFolder, accountEmail: String) {
        dao.deleteAllInFolderForAccount(folder, accountEmail)
    }

    suspend fun sendOrScheduleEmail(
        existingDraftId: Long?,
        replyToThreadId: Long?,
        senderAccount: MailAccountEntity,
        recipientsTo: List<String>,
        recipientsCc: List<String>,
        subject: String,
        body: String,
        attachments: List<AttachmentItem>,
        isConfidential: Boolean,
        scheduledForMillis: Long?
    ): Long {
        val now = System.currentTimeMillis()
        val message = ThreadMessage(
            id = UUID.randomUUID().toString(),
            senderName = senderAccount.displayName,
            senderEmail = senderAccount.email,
            senderColorHex = senderAccount.avatarColorHex,
            recipientsTo = recipientsTo,
            recipientsCc = recipientsCc,
            body = body.trim(),
            timestampMillis = now,
            attachments = attachments
        )

        if (replyToThreadId != null && scheduledForMillis == null) {
            val existing = dao.getThreadById(replyToThreadId)
            if (existing != null) {
                val updated = existing.copy(
                    messages = existing.messages + message,
                    snippet = body.trim().replace("\n", " ").take(120),
                    isRead = true,
                    updatedAtMillis = now
                )
                dao.updateThread(updated)
                if (existingDraftId != null && existingDraftId != replyToThreadId) {
                    dao.deleteThreadsByIds(listOf(existingDraftId))
                }
                return existing.id
            }
        }

        if (existingDraftId != null) {
            dao.deleteThreadsByIds(listOf(existingDraftId))
        }

        val targetFolder = if (scheduledForMillis != null) MailFolder.SCHEDULED else MailFolder.SENT
        val displayRecipient = recipientsTo.firstOrNull()?.substringBefore("@") ?: "Recipient"
        val cleanSnippet = if (scheduledForMillis != null) {
            "Scheduled · ${body.trim().replace("\n", " ").take(100)}"
        } else {
            body.trim().replace("\n", " ").take(120)
        }

        val newThread = EmailThreadEntity(
            accountEmail = senderAccount.email,
            subject = subject.ifBlank { "(no subject)" },
            snippet = cleanSnippet.ifBlank { "(no preview)" },
            primarySenderName = "To: $displayRecipient",
            primarySenderEmail = recipientsTo.firstOrNull() ?: senderAccount.email,
            primarySenderColorHex = senderAccount.avatarColorHex,
            folder = targetFolder,
            previousFolder = targetFolder,
            isRead = true,
            isConfidential = isConfidential,
            scheduledForMillis = scheduledForMillis,
            messages = listOf(message),
            updatedAtMillis = now
        )
        return dao.insertThread(newThread)
    }

    suspend fun saveDraft(
        existingDraftId: Long?,
        senderAccount: MailAccountEntity,
        recipientsTo: List<String>,
        recipientsCc: List<String>,
        subject: String,
        body: String,
        attachments: List<AttachmentItem>
    ): Long {
        val now = System.currentTimeMillis()
        val message = ThreadMessage(
            id = UUID.randomUUID().toString(),
            senderName = senderAccount.displayName,
            senderEmail = senderAccount.email,
            senderColorHex = senderAccount.avatarColorHex,
            recipientsTo = recipientsTo,
            recipientsCc = recipientsCc,
            body = body,
            timestampMillis = now,
            attachments = attachments
        )
        val draftThread = EmailThreadEntity(
            id = existingDraftId ?: 0L,
            accountEmail = senderAccount.email,
            subject = subject.ifBlank { "(no subject)" },
            snippet = "Draft: ${body.trim().replace("\n", " ").take(100)}",
            primarySenderName = "Draft",
            primarySenderEmail = senderAccount.email,
            primarySenderColorHex = 0xFFD93025,
            folder = MailFolder.DRAFT,
            previousFolder = MailFolder.DRAFT,
            isRead = true,
            messages = listOf(message),
            updatedAtMillis = now
        )
        return dao.insertThread(draftThread)
    }

    suspend fun simulateIncomingMail(accountEmail: String) {
        val now = System.currentTimeMillis()
        val samplePool = listOf(
            EmailThreadEntity(
                accountEmail = accountEmail,
                subject = "Weekly Digest: Android Performance & Jetpack Compose 2026",
                snippet = "Top tips on Baseline Profiles, LazyColumn keys, and zero-jank animations.",
                primarySenderName = "Android Weekly",
                primarySenderEmail = "editorial@androidweekly.net",
                primarySenderColorHex = 0xFF00897B,
                folder = MailFolder.PRIMARY,
                isRead = false,
                labels = listOf("Work"),
                updatedAtMillis = now,
                messages = listOf(
                    ThreadMessage(
                        id = UUID.randomUUID().toString(),
                        senderName = "Android Weekly",
                        senderEmail = "editorial@androidweekly.net",
                        senderColorHex = 0xFF00897B,
                        recipientsTo = listOf(accountEmail),
                        body = "Hi Pawan,\n\nHere are this week's curated Android engineering articles:\n\n1. Designing adaptive Material 3 navigation rails & panes\n2. Room + Flow reactive patterns for offline-first apps\n3. Minimizing recomposition overhead in dense email lists\n\nHappy coding!",
                        timestampMillis = now
                    )
                )
            ),
            EmailThreadEntity(
                accountEmail = accountEmail,
                subject = "Order shipped: USB-C Thunderbolt 4 Braided Cable",
                snippet = "Arriving tomorrow by 8:00 PM · Track your package in real time.",
                primarySenderName = "MetroStore Orders",
                primarySenderEmail = "tracking@metrostore.in",
                primarySenderColorHex = 0xFFEF6C00,
                folder = MailFolder.UPDATES,
                isRead = false,
                labels = listOf("Receipts"),
                updatedAtMillis = now,
                messages = listOf(
                    ThreadMessage(
                        id = UUID.randomUUID().toString(),
                        senderName = "MetroStore Orders",
                        senderEmail = "tracking@metrostore.in",
                        senderColorHex = 0xFFEF6C00,
                        recipientsTo = listOf(accountEmail),
                        body = "Great news! Your order #MS-90841 has shipped and is out for regional transit.",
                        timestampMillis = now
                    )
                )
            )
        )
        dao.insertThread(samplePool.random())
    }
}
