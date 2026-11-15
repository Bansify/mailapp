package com.gothwad.mail.ui.state

import com.gothwad.mail.data.model.AppSettingsEntity
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.data.model.MeetingSession

enum class BottomNavTab {
    MAIL, MEET
}

enum class QuickFilterChip(val label: String) {
    UNREAD("Unread"),
    STARRED("Starred"),
    HAS_ATTACHMENT("Has attachment"),
    IMPORTANT("Important")
}

data class SearchFilterState(
    val query: String = "",
    val onlyUnread: Boolean = false,
    val onlyStarred: Boolean = false,
    val onlyHasAttachment: Boolean = false,
    val onlySentByMe: Boolean = false,
    val recentSearches: List<String> = listOf("GothwadTech", "Boarding pass", "Invoice", "Ananya")
)

data class ComposeInitialData(
    val draftId: Long? = null,
    val replyToThreadId: Long? = null,
    val modeTitle: String = "Compose",
    val initialTo: List<String> = emptyList(),
    val initialCc: List<String> = emptyList(),
    val initialSubject: String = "",
    val initialBody: String = "",
    val initialAttachments: List<AttachmentItem> = emptyList()
)

data class MailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val activeAccount: MailAccountEntity? = null,
    val allAccounts: List<MailAccountEntity> = emptyList(),
    val settings: AppSettingsEntity = AppSettingsEntity(),
    val selectedFolder: MailFolder = MailFolder.PRIMARY,
    val selectedLabelFilter: String? = null,
    val activeBottomTab: BottomNavTab = BottomNavTab.MAIL,
    val activeQuickFilters: Set<QuickFilterChip> = emptySet(),
    val visibleThreads: List<EmailThreadEntity> = emptyList(),
    val folderUnreadCounts: Map<MailFolder, Int> = emptyMap(),
    val draftCount: Int = 0,
    val availableLabels: List<String> = listOf("Work", "Design", "Travel", "Receipts", "Personal"),
    val selectedThreadIds: Set<Long> = emptySet(),
    val activeTabletThreadId: Long? = null,
    val searchState: SearchFilterState = SearchFilterState(),
    val searchResults: List<EmailThreadEntity> = emptyList(),
    val meetings: List<MeetingSession> = emptyList(),
    val activeMeetingCode: String? = null
) {
    val isSelectionMode: Boolean
        get() = selectedThreadIds.isNotEmpty()
}
