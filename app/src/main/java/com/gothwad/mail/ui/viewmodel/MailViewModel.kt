package com.gothwad.mail.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gothwad.mail.data.local.SeedData
import com.gothwad.mail.data.model.AppSettingsEntity
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.data.model.MeetingSession
import com.gothwad.mail.data.model.SwipeAction
import com.gothwad.mail.data.model.ThemePreference
import com.gothwad.mail.data.repository.MailRepository
import com.gothwad.mail.ui.state.BottomNavTab
import com.gothwad.mail.ui.state.ComposeInitialData
import com.gothwad.mail.ui.state.MailUiState
import com.gothwad.mail.ui.state.QuickFilterChip
import com.gothwad.mail.ui.state.SearchFilterState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface MailSnackbarEvent {
    data class MessageWithUndo(
        val message: String,
        val affectedThreads: List<EmailThreadEntity>,
        val actionLabel: String = "Undo"
    ) : MailSnackbarEvent

    data class SimpleMessage(val message: String) : MailSnackbarEvent
}

private data class LocalNavState(
    val selectedFolder: MailFolder = MailFolder.PRIMARY,
    val selectedLabelFilter: String? = null,
    val activeBottomTab: BottomNavTab = BottomNavTab.MAIL,
    val activeQuickFilters: Set<QuickFilterChip> = emptySet(),
    val selectedThreadIds: Set<Long> = emptySet(),
    val activeTabletThreadId: Long? = null,
    val isRefreshing: Boolean = false,
    val searchState: SearchFilterState = SearchFilterState(),
    val meetings: List<MeetingSession> = SeedData.initialMeetings(),
    val activeMeetingCode: String? = null
)

class MailViewModel(private val repository: MailRepository) : ViewModel() {

    private val localState = MutableStateFlow(LocalNavState())
    private val _snackbarEvents = MutableSharedFlow<MailSnackbarEvent>()
    val snackbarEvents: SharedFlow<MailSnackbarEvent> = _snackbarEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }
    }

    val uiState: StateFlow<MailUiState> = combine(
        repository.allThreads,
        repository.accounts,
        repository.settings,
        localState
    ) { allThreads, accounts, settings, nav ->
        val activeAccount = accounts.firstOrNull { it.isActive } ?: accounts.firstOrNull()
        val accountThreads = if (nav.selectedFolder == MailFolder.ALL_INBOXES || activeAccount == null) {
            allThreads
        } else {
            allThreads.filter { it.accountEmail == activeAccount.email }
        }

        val folderThreads = filterThreadsForFolder(
            threads = accountThreads,
            folder = nav.selectedFolder,
            labelFilter = nav.selectedLabelFilter,
            quickFilters = nav.activeQuickFilters
        )

        val unreadCounts = computeUnreadCounts(accountThreads)
        val draftCount = accountThreads.count { it.folder == MailFolder.DRAFT }

        val searchResults = filterForSearch(
            threads = allThreads.filter {
                activeAccount == null || it.accountEmail == activeAccount.email
            },
            search = nav.searchState
        )

        val dynamicLabels = (listOf("Work", "Design", "Travel", "Receipts", "Personal") +
            accountThreads.flatMap { it.labels }).distinct().sorted()

        MailUiState(
            isLoading = accounts.isEmpty(),
            isRefreshing = nav.isRefreshing,
            activeAccount = activeAccount,
            allAccounts = accounts,
            settings = settings,
            selectedFolder = nav.selectedFolder,
            selectedLabelFilter = nav.selectedLabelFilter,
            activeBottomTab = nav.activeBottomTab,
            activeQuickFilters = nav.activeQuickFilters,
            visibleThreads = folderThreads,
            folderUnreadCounts = unreadCounts,
            draftCount = draftCount,
            availableLabels = dynamicLabels,
            selectedThreadIds = nav.selectedThreadIds,
            activeTabletThreadId = nav.activeTabletThreadId,
            searchState = nav.searchState,
            searchResults = searchResults,
            meetings = nav.meetings,
            activeMeetingCode = nav.activeMeetingCode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MailUiState()
    )

    fun observeThread(threadId: Long): Flow<EmailThreadEntity?> = repository.observeThread(threadId)

    fun selectFolder(folder: MailFolder) {
        localState.update {
            it.copy(
                selectedFolder = folder,
                selectedLabelFilter = null,
                activeBottomTab = BottomNavTab.MAIL,
                selectedThreadIds = emptySet(),
                activeQuickFilters = emptySet()
            )
        }
    }

    fun selectLabel(label: String) {
        localState.update {
            it.copy(
                selectedFolder = MailFolder.ALL_MAIL,
                selectedLabelFilter = label,
                activeBottomTab = BottomNavTab.MAIL,
                selectedThreadIds = emptySet()
            )
        }
    }

    fun selectBottomTab(tab: BottomNavTab) {
        localState.update { it.copy(activeBottomTab = tab, selectedThreadIds = emptySet()) }
    }

    fun toggleQuickFilter(chip: QuickFilterChip) {
        localState.update { state ->
            val updated = if (chip in state.activeQuickFilters) {
                state.activeQuickFilters - chip
            } else {
                state.activeQuickFilters + chip
            }
            state.copy(activeQuickFilters = updated)
        }
    }

    fun selectTabletThread(threadId: Long?) {
        localState.update { it.copy(activeTabletThreadId = threadId) }
        if (threadId != null) {
            markThreadRead(threadId, true)
        }
    }

    fun refreshInbox() {
        val activeEmail = uiState.value.activeAccount?.email ?: SeedData.PRIMARY_EMAIL
        viewModelScope.launch {
            localState.update { it.copy(isRefreshing = true) }
            delay(650)
            repository.simulateIncomingMail(activeEmail)
            localState.update { it.copy(isRefreshing = false) }
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Inbox synced"))
        }
    }

    fun toggleThreadSelection(threadId: Long) {
        localState.update { state ->
            val updated = if (threadId in state.selectedThreadIds) {
                state.selectedThreadIds - threadId
            } else {
                state.selectedThreadIds + threadId
            }
            state.copy(selectedThreadIds = updated)
        }
    }

    fun clearSelection() {
        localState.update { it.copy(selectedThreadIds = emptySet()) }
    }

    fun selectAllVisible() {
        val visibleIds = uiState.value.visibleThreads.map { it.id }.toSet()
        localState.update { it.copy(selectedThreadIds = visibleIds) }
    }

    fun markThreadRead(threadId: Long, isRead: Boolean) {
        viewModelScope.launch {
            repository.markThreadsRead(listOf(threadId), isRead)
        }
    }

    fun markSelectedRead(isRead: Boolean) {
        val ids = localState.value.selectedThreadIds.toList()
        localState.update { it.copy(selectedThreadIds = emptySet()) }
        viewModelScope.launch {
            repository.markThreadsRead(ids, isRead)
            _snackbarEvents.emit(
                MailSnackbarEvent.SimpleMessage(
                    if (isRead) "${ids.size} marked as read" else "${ids.size} marked as unread"
                )
            )
        }
    }

    fun toggleStar(thread: EmailThreadEntity) {
        viewModelScope.launch {
            repository.toggleStar(thread)
        }
    }

    fun toggleImportant(thread: EmailThreadEntity) {
        viewModelScope.launch {
            repository.toggleImportant(thread)
        }
    }

    fun handleSwipe(thread: EmailThreadEntity, action: SwipeAction) {
        viewModelScope.launch {
            when (action) {
                SwipeAction.ARCHIVE -> archiveThreads(listOf(thread))
                SwipeAction.DELETE -> trashThreads(listOf(thread))
                SwipeAction.MARK_READ -> {
                    val nextRead = !thread.isRead
                    repository.markThreadsRead(listOf(thread.id), nextRead)
                    _snackbarEvents.emit(
                        MailSnackbarEvent.SimpleMessage(
                            if (nextRead) "Marked as read" else "Marked as unread"
                        )
                    )
                }
            }
        }
    }

    fun archiveSelected() {
        val selected = uiState.value.visibleThreads.filter { it.id in localState.value.selectedThreadIds }
        localState.update { it.copy(selectedThreadIds = emptySet()) }
        archiveThreads(selected)
    }

    fun archiveThreads(threads: List<EmailThreadEntity>) {
        if (threads.isEmpty()) return
        viewModelScope.launch {
            repository.archiveThreads(threads)
            if (localState.value.activeTabletThreadId in threads.map { it.id }) {
                localState.update { it.copy(activeTabletThreadId = null) }
            }
            _snackbarEvents.emit(
                MailSnackbarEvent.MessageWithUndo(
                    message = if (threads.size == 1) "Archived" else "${threads.size} archived",
                    affectedThreads = threads
                )
            )
        }
    }

    fun trashSelected() {
        val selected = uiState.value.visibleThreads.filter { it.id in localState.value.selectedThreadIds }
        localState.update { it.copy(selectedThreadIds = emptySet()) }
        trashThreads(selected)
    }

    fun trashThreads(threads: List<EmailThreadEntity>) {
        if (threads.isEmpty()) return
        viewModelScope.launch {
            val inTrash = threads.all { it.folder == MailFolder.TRASH }
            if (inTrash) {
                repository.deleteForever(threads.map { it.id })
                _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Deleted forever"))
            } else {
                repository.moveThreadsToTrash(threads)
                _snackbarEvents.emit(
                    MailSnackbarEvent.MessageWithUndo(
                        message = if (threads.size == 1) "Moved to Trash" else "${threads.size} moved to Trash",
                        affectedThreads = threads
                    )
                )
            }
            if (localState.value.activeTabletThreadId in threads.map { it.id }) {
                localState.update { it.copy(activeTabletThreadId = null) }
            }
        }
    }

    fun undoFolderMove(threads: List<EmailThreadEntity>) {
        viewModelScope.launch {
            repository.restoreThreads(threads)
        }
    }

    fun reportSpam(thread: EmailThreadEntity) {
        viewModelScope.launch {
            repository.moveThreadsToSpam(listOf(thread))
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Reported as spam"))
        }
    }

    fun moveThreadToFolder(thread: EmailThreadEntity, folder: MailFolder) {
        viewModelScope.launch {
            repository.moveThreadToFolder(thread, folder)
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Moved to ${folder.displayName}"))
        }
    }

    fun snoozeThread(thread: EmailThreadEntity, untilLabel: String) {
        viewModelScope.launch {
            repository.snoozeThread(thread, untilLabel)
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Snoozed until $untilLabel"))
        }
    }

    fun updateLabels(thread: EmailThreadEntity, labels: List<String>) {
        viewModelScope.launch {
            repository.updateThreadLabels(thread, labels)
        }
    }

    fun emptyCurrentFolder() {
        val state = uiState.value
        val account = state.activeAccount?.email ?: return
        if (state.selectedFolder != MailFolder.TRASH && state.selectedFolder != MailFolder.SPAM) return
        viewModelScope.launch {
            repository.emptyFolder(state.selectedFolder, account)
            _snackbarEvents.emit(
                MailSnackbarEvent.SimpleMessage("${state.selectedFolder.displayName} emptied")
            )
        }
    }

    fun switchAccount(email: String) {
        viewModelScope.launch {
            repository.switchAccount(email)
            localState.update { it.copy(selectedThreadIds = emptySet(), activeTabletThreadId = null) }
        }
    }

    fun addAccount(displayName: String, email: String) {
        if (displayName.isBlank() || email.isBlank()) return
        val palette = listOf(0xFF0B57D0, 0xFF7B1FA2, 0xFF00838F, 0xFFD84315, 0xFF146C2E)
        viewModelScope.launch {
            repository.addAccount(displayName, email, palette.random())
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Switched to $email"))
        }
    }

    fun updateAccountSettings(account: MailAccountEntity) {
        viewModelScope.launch {
            repository.updateAccount(account)
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Account preferences saved"))
        }
    }

    fun updateSettings(settings: AppSettingsEntity) {
        viewModelScope.launch {
            repository.updateSettings(settings)
        }
    }

    fun updateTheme(themePreference: ThemePreference) {
        val current = uiState.value.settings
        updateSettings(current.copy(themePreference = themePreference))
    }

    // Search
    fun updateSearchQuery(query: String) {
        localState.update { it.copy(searchState = it.searchState.copy(query = query)) }
    }

    fun commitSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        localState.update { state ->
            val updated = (listOf(trimmed) + state.searchState.recentSearches).distinct().take(6)
            state.copy(searchState = state.searchState.copy(recentSearches = updated))
        }
    }

    fun clearSearchHistory() {
        localState.update { it.copy(searchState = it.searchState.copy(recentSearches = emptyList())) }
    }

    fun toggleSearchFilter(
        unread: Boolean? = null,
        starred: Boolean? = null,
        hasAttachment: Boolean? = null,
        sentByMe: Boolean? = null
    ) {
        localState.update { state ->
            val s = state.searchState
            state.copy(
                searchState = s.copy(
                    onlyUnread = unread ?: s.onlyUnread,
                    onlyStarred = starred ?: s.onlyStarred,
                    onlyHasAttachment = hasAttachment ?: s.onlyHasAttachment,
                    onlySentByMe = sentByMe ?: s.onlySentByMe
                )
            )
        }
    }

    fun resetSearch() {
        localState.update { state ->
            state.copy(
                searchState = SearchFilterState(recentSearches = state.searchState.recentSearches)
            )
        }
    }

    // Compose / Reply / Forward
    fun sendOrScheduleEmail(
        draftId: Long?,
        replyToThreadId: Long?,
        fromAccount: MailAccountEntity,
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String,
        attachments: List<AttachmentItem>,
        isConfidential: Boolean,
        scheduledForMillis: Long?
    ) {
        viewModelScope.launch {
            repository.sendOrScheduleEmail(
                existingDraftId = draftId,
                replyToThreadId = replyToThreadId,
                senderAccount = fromAccount,
                recipientsTo = to,
                recipientsCc = cc,
                subject = subject,
                body = body,
                attachments = attachments,
                isConfidential = isConfidential,
                scheduledForMillis = scheduledForMillis
            )
            _snackbarEvents.emit(
                MailSnackbarEvent.SimpleMessage(
                    if (scheduledForMillis != null) "Email scheduled" else "Message sent"
                )
            )
        }
    }

    fun saveDraft(
        draftId: Long?,
        fromAccount: MailAccountEntity,
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String,
        attachments: List<AttachmentItem>
    ) {
        if (to.isEmpty() && subject.isBlank() && body.isBlank() && attachments.isEmpty()) return
        viewModelScope.launch {
            repository.saveDraft(draftId, fromAccount, to, cc, subject, body, attachments)
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Draft saved"))
        }
    }

    fun buildComposeForReply(
        thread: EmailThreadEntity,
        replyAll: Boolean,
        forward: Boolean
    ): ComposeInitialData {
        val lastMessage = thread.messages.lastOrNull()
        val activeEmail = uiState.value.activeAccount?.email ?: SeedData.PRIMARY_EMAIL
        val prefix = if (forward) "Fwd: " else "Re: "
        val subject = if (thread.subject.startsWith(prefix, ignoreCase = true)) {
            thread.subject
        } else {
            "$prefix${thread.subject}"
        }
        val toList = when {
            forward -> emptyList()
            lastMessage != null && lastMessage.senderEmail != activeEmail -> listOf(lastMessage.senderEmail)
            else -> listOf(thread.primarySenderEmail)
        }
        val ccList = if (replyAll && lastMessage != null) {
            (lastMessage.recipientsTo + lastMessage.recipientsCc)
                .filter { it != activeEmail && it !in toList }
                .distinct()
        } else {
            emptyList()
        }
        val quotedBody = if (lastMessage != null) {
            "\n\n----------\nOn previous message, ${lastMessage.senderName} <${lastMessage.senderEmail}> wrote:\n> ${
                lastMessage.body.replace("\n", "\n> ")
            }"
        } else {
            ""
        }
        return ComposeInitialData(
            replyToThreadId = if (forward) null else thread.id,
            modeTitle = when {
                forward -> "Forward"
                replyAll -> "Reply all"
                else -> "Reply"
            },
            initialTo = toList,
            initialCc = ccList,
            initialSubject = subject,
            initialBody = quotedBody,
            initialAttachments = if (forward && lastMessage != null) lastMessage.attachments else emptyList()
        )
    }

    fun buildComposeFromDraft(thread: EmailThreadEntity): ComposeInitialData {
        val msg = thread.messages.lastOrNull()
        return ComposeInitialData(
            draftId = thread.id,
            modeTitle = "Edit draft",
            initialTo = msg?.recipientsTo ?: emptyList(),
            initialCc = msg?.recipientsCc ?: emptyList(),
            initialSubject = if (thread.subject == "(no subject)") "" else thread.subject,
            initialBody = msg?.body ?: "",
            initialAttachments = msg?.attachments ?: emptyList()
        )
    }

    // Meet Actions
    fun createNewMeeting(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz"
        val seg1 = (1..3).map { chars.random() }.joinToString("")
        val seg2 = (1..4).map { chars.random() }.joinToString("")
        val seg3 = (1..3).map { chars.random() }.joinToString("")
        val code = "$seg1-$seg2-$seg3"
        val newSession = MeetingSession(
            id = "meet-${System.currentTimeMillis()}",
            title = "Instant Video Meeting",
            code = code,
            timeLabel = "Starting now",
            hostName = uiState.value.activeAccount?.displayName ?: "You",
            participantCount = 1
        )
        localState.update {
            it.copy(
                meetings = listOf(newSession) + it.meetings,
                activeMeetingCode = code
            )
        }
        return code
    }

    fun joinMeeting(code: String) {
        val normalized = code.trim().lowercase()
        if (normalized.isBlank()) return
        localState.update { it.copy(activeMeetingCode = normalized) }
    }

    fun leaveMeeting() {
        localState.update { it.copy(activeMeetingCode = null) }
    }

    private fun filterThreadsForFolder(
        threads: List<EmailThreadEntity>,
        folder: MailFolder,
        labelFilter: String?,
        quickFilters: Set<QuickFilterChip>
    ): List<EmailThreadEntity> {
        val base = when {
            labelFilter != null -> threads.filter {
                labelFilter in it.labels && it.folder != MailFolder.TRASH && it.folder != MailFolder.SPAM
            }
            folder == MailFolder.ALL_INBOXES -> threads.filter { it.folder.isInboxCategory }
            folder == MailFolder.STARRED -> threads.filter {
                it.isStarred && it.folder != MailFolder.TRASH && it.folder != MailFolder.SPAM
            }
            folder == MailFolder.IMPORTANT -> threads.filter {
                it.isImportant && it.folder != MailFolder.TRASH && it.folder != MailFolder.SPAM
            }
            folder == MailFolder.ALL_MAIL -> threads.filter {
                it.folder != MailFolder.TRASH && it.folder != MailFolder.SPAM
            }
            else -> threads.filter { it.folder == folder }
        }

        return base.filter { thread ->
            val unreadOk = QuickFilterChip.UNREAD !in quickFilters || !thread.isRead
            val starredOk = QuickFilterChip.STARRED !in quickFilters || thread.isStarred
            val attachmentOk = QuickFilterChip.HAS_ATTACHMENT !in quickFilters || thread.hasAttachments
            val importantOk = QuickFilterChip.IMPORTANT !in quickFilters || thread.isImportant
            unreadOk && starredOk && attachmentOk && importantOk
        }
    }

    private fun computeUnreadCounts(threads: List<EmailThreadEntity>): Map<MailFolder, Int> {
        val map = mutableMapOf<MailFolder, Int>()
        MailFolder.entries.forEach { folder ->
            val count = when (folder) {
                MailFolder.ALL_INBOXES -> threads.count { it.folder.isInboxCategory && !it.isRead }
                MailFolder.STARRED -> threads.count { it.isStarred && !it.isRead && it.folder != MailFolder.TRASH }
                MailFolder.IMPORTANT -> threads.count { it.isImportant && !it.isRead && it.folder != MailFolder.TRASH }
                MailFolder.ALL_MAIL -> threads.count { !it.isRead && it.folder != MailFolder.TRASH && it.folder != MailFolder.SPAM }
                else -> threads.count { it.folder == folder && !it.isRead }
            }
            if (count > 0) map[folder] = count
        }
        return map
    }

    private fun filterForSearch(
        threads: List<EmailThreadEntity>,
        search: SearchFilterState
    ): List<EmailThreadEntity> {
        val q = search.query.trim()
        val hasFilters = search.onlyUnread || search.onlyStarred || search.onlyHasAttachment || search.onlySentByMe
        if (q.isEmpty() && !hasFilters) return emptyList()

        return threads.filter { thread ->
            if (thread.folder == MailFolder.TRASH) return@filter false
            val matchesQuery = q.isEmpty() ||
                thread.subject.contains(q, ignoreCase = true) ||
                thread.primarySenderName.contains(q, ignoreCase = true) ||
                thread.primarySenderEmail.contains(q, ignoreCase = true) ||
                thread.snippet.contains(q, ignoreCase = true) ||
                thread.messages.any { it.body.contains(q, ignoreCase = true) }

            val unreadOk = !search.onlyUnread || !thread.isRead
            val starredOk = !search.onlyStarred || thread.isStarred
            val attachOk = !search.onlyHasAttachment || thread.hasAttachments
            val sentOk = !search.onlySentByMe || thread.folder == MailFolder.SENT
            matchesQuery && unreadOk && starredOk && attachOk && sentOk
        }
    }

    class Factory(private val repository: MailRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MailViewModel(repository) as T
        }
    }
}
