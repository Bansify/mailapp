package com.gothwad.mail.ui.state

import com.gothwad.mail.auth.MailAuthFailure
import com.gothwad.mail.auth.broker.DiscoveredAccount
import com.gothwad.mail.data.model.AppSettingsEntity
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.data.model.MeetingSession
import com.gothwad.mail.data.model.RetentionPolicy

enum class BottomNavTab {
    MAIL, MEET
}

enum class QuickFilterChip(val label: String) {
    UNREAD("Unread"),
    STARRED("Starred"),
    HAS_ATTACHMENT("Has attachment"),
    IMPORTANT("Important")
}

/** Where the app stands with Gothwad Accounts, driving the signed-out screen. */
enum class AuthScreenState {
    LOADING,
    SIGNED_OUT,
    SIGNED_IN
}

data class SearchFilterState(
    val query: String = "",
    val onlyUnread: Boolean = false,
    val onlyStarred: Boolean = false,
    val onlyHasAttachment: Boolean = false,
    val onlySentByMe: Boolean = false,
    val recentSearches: List<String> = emptyList()
)

data class ComposeInitialData(
    val draftId: String? = null,
    val replyToThreadId: String? = null,
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
    val authState: AuthScreenState = AuthScreenState.LOADING,
    val authFailure: MailAuthFailure? = null,
    val activeAccount: MailAccountEntity? = null,
    val allAccounts: List<MailAccountEntity> = emptyList(),
    /** Accounts found on this device through the verified peer directory, not signed in yet. */
    val discoveredAccounts: List<DiscoveredAccount> = emptyList(),
    /** Username of the account whose one-tap sign-in is in flight, if any. */
    val pendingSignInUsername: String? = null,
    val settings: AppSettingsEntity = AppSettingsEntity(),
    val selectedFolder: MailFolder = MailFolder.PRIMARY,
    val selectedLabelFilter: String? = null,
    val activeBottomTab: BottomNavTab = BottomNavTab.MAIL,
    val activeQuickFilters: Set<QuickFilterChip> = emptySet(),
    val visibleThreads: List<EmailThreadEntity> = emptyList(),
    val folderUnreadCounts: Map<MailFolder, Int> = emptyMap(),
    val draftCount: Int = 0,
    val availableLabels: List<String> = emptyList(),
    val selectedThreadIds: Set<String> = emptySet(),
    val activeTabletThreadId: String? = null,
    val searchState: SearchFilterState = SearchFilterState(),
    val searchResults: List<EmailThreadEntity> = emptyList(),
    val isSearching: Boolean = false,
    val meetings: List<MeetingSession> = emptyList(),
    val activeMeetingCode: String? = null,
    /** Remaining sends today from `GET /api/session`; `null` when unknown. */
    val sendRemainingToday: Int? = null,
    val dailySendLimit: Int? = null,
    val retention: RetentionPolicy = RetentionPolicy.KEEP_500,
    val syncError: String? = null
) {
    val isSelectionMode: Boolean
        get() = selectedThreadIds.isNotEmpty()

    val isSignedIn: Boolean
        get() = authState == AuthScreenState.SIGNED_IN

    /** Accounts on this device that the user has not authorized for Mail yet. */
    val availableToAdd: List<DiscoveredAccount>
        get() = discoveredAccounts.filterNot { discovered ->
            discovered.isAuthorizedLocally ||
                allAccounts.any {
                    it.username.equals(discovered.username, ignoreCase = true) ||
                        it.email.equals(discovered.email, ignoreCase = true)
                }
        }
}

/** Steps of the native, browser-less sign-in sheet. */
enum class NativeAuthStep { IDENTIFIER, PASSWORD, MFA, SIGN_UP, RECOVERY }

/**
 * State of the in-app native sign-in / sign-up / 2FA sheet.
 *
 * The sheet talks to `/api/mobile/auth/*` directly, so it works with no browser installed.
 * [hint] carries non-error information (e.g. "check your recovery email") and [error] is shown
 * as a failure.
 */
data class NativeAuthUiState(
    val visible: Boolean = false,
    val step: NativeAuthStep = NativeAuthStep.IDENTIFIER,
    val identifier: String = "",
    val challenge: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val hint: String? = null,
)
