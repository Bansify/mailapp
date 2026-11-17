package com.gothwad.mail.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gothwad.mail.auth.AuthSessionState
import com.gothwad.mail.auth.AuthEvent
import com.gothwad.mail.auth.GothwadMailAuth
import com.gothwad.mail.auth.MailAuthAccount
import com.gothwad.mail.auth.ApiNativeSignUpRequest
import com.gothwad.mail.auth.MailAuthFailure
import com.gothwad.mail.auth.NativeAuthOutcome
import com.gothwad.mail.auth.broker.DiscoveredAccount
import com.gothwad.mail.data.model.AppSettingsEntity
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.data.model.MeetingSession
import com.gothwad.mail.data.model.RetentionPolicy
import com.gothwad.mail.data.model.SwipeAction
import com.gothwad.mail.data.model.ThemePreference
import com.gothwad.mail.data.repository.MailMappers
import com.gothwad.mail.data.repository.MailRepository
import com.gothwad.mail.data.remote.MailApiException
import com.gothwad.mail.data.remote.MailAccessTokenProvider
import com.gothwad.mail.data.remote.sendFailureMessage
import com.gothwad.mail.ui.state.AuthScreenState
import com.gothwad.mail.ui.state.BottomNavTab
import com.gothwad.mail.ui.state.ComposeInitialData
import com.gothwad.mail.ui.state.MailUiState
import com.gothwad.mail.ui.state.NativeAuthStep
import com.gothwad.mail.ui.state.NativeAuthUiState
import com.gothwad.mail.ui.state.QuickFilterChip
import com.gothwad.mail.ui.state.SearchFilterState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    val selectedThreadIds: Set<String> = emptySet(),
    val activeTabletThreadId: String? = null,
    val isRefreshing: Boolean = false,
    val searchState: SearchFilterState = SearchFilterState(),
    val searchResults: List<EmailThreadEntity> = emptyList(),
    val isSearching: Boolean = false,
    val meetings: List<MeetingSession> = emptyList(),
    val activeMeetingCode: String? = null,
    val syncError: String? = null
)

/**
 * Single source of truth for the Mail UI.
 *
 * Everything the screens show comes from Room, scoped to the active Gothwad account. The ViewModel
 * refreshes Room from the Mail Worker API and forwards user actions back to the API, so the app is
 * offline-first and account-isolated.
 */
class MailViewModel(
    private val repository: MailRepository,
    private val auth: GothwadMailAuth,
    private val tokenProvider: MailAccessTokenProvider,
) : ViewModel() {

    private val localState = MutableStateFlow(LocalNavState())

    /** Drives the native (browser-less) sign-in sheet. */
    private val _nativeAuth = MutableStateFlow(NativeAuthUiState())
    val nativeAuth: StateFlow<NativeAuthUiState> = _nativeAuth.asStateFlow()
    private val _snackbarEvents = MutableSharedFlow<MailSnackbarEvent>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<MailSnackbarEvent> = _snackbarEvents.asSharedFlow()

    private var searchJob: Job? = null
    private var syncJob: Job? = null
    private var lastSyncedFolder: MailFolder? = null
    private var lastSyncedAccount: String? = null

    private val activeAccountFlow: Flow<MailAccountEntity?> = repository.observeActiveAccount()

    /** Threads of the active account only: switching accounts re-subscribes to a new query. */
    private val activeThreadsFlow: Flow<List<EmailThreadEntity>> = activeAccountFlow
        .distinctUntilChanged { old, new -> old?.email == new?.email }
        .flatMapLatest { account ->
            if (account == null) flowOf(emptyList()) else repository.observeThreadsForAccount(account.email)
        }

    init {
        viewModelScope.launch {
            repository.ensureInitialized()
            auth.events.collect { event -> handleAuthEvent(event) }
        }
        viewModelScope.launch {
            // Reconcile the signed-in accounts with the local account table on startup.
            auth.state.collect { state ->
                if (state is AuthSessionState.SignedIn) {
                    syncAccountsToCache(state.accounts, state.activeAccount)
                }
            }
        }
        viewModelScope.launch {
            activeAccountFlow.distinctUntilChanged { old, new -> old?.email == new?.email }
                .collect { account ->
                    if (account != null && lastSyncedAccount != account.email) {
                        lastSyncedAccount = account.email
                        refreshCurrentFolder(showSpinner = false)
                    }
                }
        }
    }

    /** Auth-derived state, grouped so the main combine stays on the typed 5-flow overload. */
    private data class AuthSlice(
        val state: AuthSessionState,
        val failure: MailAuthFailure?,
        val discovered: List<DiscoveredAccount>,
    )

    private val authSlice: Flow<AuthSlice> = combine(
        auth.state,
        auth.sessionManager.failure,
        auth.discoveredAccounts,
    ) { state, failure, discovered -> AuthSlice(state, failure, discovered) }

    val uiState: StateFlow<MailUiState> = combine(
        activeThreadsFlow,
        repository.accounts,
        repository.settings,
        authSlice,
        localState
    ) { activeThreads, accounts, settings, authSliceValue, nav ->
        val authState = authSliceValue.state
        val discovered = authSliceValue.discovered
        val activeAccount = accounts.firstOrNull { it.isActive } ?: accounts.firstOrNull()
        val accountThreads = activeThreads

        val folderThreads = filterThreadsForFolder(
            threads = accountThreads,
            folder = nav.selectedFolder,
            labelFilter = nav.selectedLabelFilter,
            quickFilters = nav.activeQuickFilters
        )
        val unreadCounts = computeUnreadCounts(accountThreads)
        val draftCount = accountThreads.count { it.folder == MailFolder.DRAFT }
        val localSearch = filterForSearch(accountThreads, nav.searchState)

        val dynamicLabels = accountThreads.flatMap { it.labels }.distinct().sorted()

        val screenState = when (authState) {
            is AuthSessionState.Initializing -> AuthScreenState.LOADING
            is AuthSessionState.SignedOut -> AuthScreenState.SIGNED_OUT
            is AuthSessionState.StorageCorrupted -> AuthScreenState.SIGNED_OUT
            is AuthSessionState.SignedIn -> AuthScreenState.SIGNED_IN
        }

        MailUiState(
            isLoading = authState is AuthSessionState.Initializing,
            isRefreshing = nav.isRefreshing,
            authState = screenState,
            authFailure = authSliceValue.failure ?: when (authState) {
                is AuthSessionState.StorageCorrupted -> MailAuthFailure.StorageUnavailable
                else -> null
            },
            activeAccount = activeAccount,
            allAccounts = accounts,
            discoveredAccounts = discovered,
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
            searchResults = if (nav.searchResults.isNotEmpty()) nav.searchResults else localSearch,
            isSearching = nav.isSearching,
            meetings = nav.meetings,
            activeMeetingCode = nav.activeMeetingCode,
            retention = activeAccount?.retention ?: RetentionPolicy.KEEP_500,
            syncError = nav.syncError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MailUiState()
    )

    // --- Auth ---------------------------------------------------------------

    private suspend fun handleAuthEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.SignedIn -> {
                repository.activeAccount()?.let { refreshCurrentFolder(showSpinner = true) }
                _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Signed in as ${event.account.username}"))
            }
            is AuthEvent.SignedOut -> {
                repository.removeAccount(event.account?.email ?: return)
                lastSyncedAccount = null
                _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage(describeSignOut(event.outcome)))
            }
            is AuthEvent.SilentSignInSucceeded ->
                _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Signed in as ${event.account.username}"))

            is AuthEvent.SilentSignInFailed -> Unit

            AuthEvent.LoginCancelled -> _snackbarEvents.emit(
                MailSnackbarEvent.SimpleMessage("Sign-in was cancelled"),
            )
            AuthEvent.BrowserUnavailable -> _snackbarEvents.emit(
                MailSnackbarEvent.SimpleMessage("No browser is available to sign in"),
            )
            is AuthEvent.Failed -> _snackbarEvents.emit(
                MailSnackbarEvent.SimpleMessage(describeFailure(event.failure)),
            )
        }
    }

    private fun describeSignOut(outcome: com.gothwad.mail.auth.DisconnectOutcome): String = when (outcome) {
        com.gothwad.mail.auth.DisconnectOutcome.RevokedOnServer -> "Signed out and disconnected on the server"
        com.gothwad.mail.auth.DisconnectOutcome.AlreadyInvalidOnServer -> "Signed out; the grant was already invalid"
        com.gothwad.mail.auth.DisconnectOutcome.OfflineRemovedLocally ->
            "Signed out on this device; the server grant could not be confirmed"
        com.gothwad.mail.auth.DisconnectOutcome.LocalOnly -> "Signed out of Gothwad Mail"
    }

    private fun describeFailure(failure: MailAuthFailure): String = when (failure) {
        MailAuthFailure.BrowserUnavailable -> "No browser is available to sign in"
        MailAuthFailure.InvalidCallback -> "The sign-in response was invalid. Try again."
        MailAuthFailure.CallbackExpired -> "That sign-in attempt expired. Tap Sign in with Gothwad to try again."
        MailAuthFailure.LoginCancelled -> "Sign-in was cancelled"
        MailAuthFailure.SessionExpired -> "Your Gothwad Account session expired. Sign in again."
        MailAuthFailure.StorageUnavailable -> "Encrypted account storage is unavailable"
        MailAuthFailure.Unavailable -> "Could not reach Gothwad Accounts"
    }

    private suspend fun syncAccountsToCache(accounts: List<MailAuthAccount>, active: MailAuthAccount?) {
        accounts.forEach { account ->
            val existing = repository.accountsSnapshot().find { it.email == account.email }
            repository.upsertAccount(
                MailMappers.toAccount(
                    com.gothwad.mail.auth.GothwadUserInfo(
                        userId = account.userId,
                        username = account.username,
                        name = account.displayName,
                        email = account.email,
                        picture = account.avatarUrl,
                    ),
                    existing = existing,
                    active = account.userId == (active?.userId ?: account.userId),
                ),
            )
        }
        // Drop cached rows for accounts that no longer exist in the encrypted store.
        val known = accounts.map { it.email }.toSet()
        repository.accountsSnapshot().forEach { cached ->
            if (cached.email !in known) repository.removeAccount(cached.email)
        }
        active?.let { repository.switchAccount(it.email) }
    }

    /**
     * One-tap silent sign-in for an account discovered through a verified sibling app.
     *
     * This replaces the browser hop: the peer issues a PKCE code over Binder IPC and the app
     * redeems it in-process, so no browser and no permission screen is shown.
     */
    fun signInSilently(discovered: DiscoveredAccount) {
        viewModelScope.launch {
            _nativeAuth.update { state -> state.copy(busy = true, error = null) }
            val account = auth.signInSilently(discovered)
            _nativeAuth.update { state -> state.copy(busy = false) }
            if (account != null) {
                repository.activeAccount()?.let { refreshCurrentFolder(showSpinner = true) }
            } else {
                // Fall back to the native sheet, pre-filled with the discovered identity.
                openNativeSignIn(prefillIdentifier = discovered.email ?: discovered.username)
                _nativeAuth.update { state ->
                    state.copy(hint = "Silent sign-in was not available. Enter your password to continue.")
                }
            }
        }
    }

    // --- Native auth (no browser) -------------------------------------------

    fun openNativeSignIn(prefillIdentifier: String? = null, startOnSignUp: Boolean = false) {
        _nativeAuth.value = NativeAuthUiState(
            visible = true,
            step = if (startOnSignUp) NativeAuthStep.SIGN_UP else NativeAuthStep.IDENTIFIER,
            identifier = prefillIdentifier.orEmpty(),
        )
    }

    fun openNativeSignUp() = openNativeSignIn(startOnSignUp = true)

    fun dismissNativeAuth() {
        _nativeAuth.value = NativeAuthUiState()
    }

    fun nativeAuthUpdate(transform: (NativeAuthUiState) -> NativeAuthUiState) {
        _nativeAuth.update { current -> transform(current) }
    }

    /** Step 1 -> step 2: confirm the identifier exists, then ask for the password. */
    fun nativeSubmitIdentifier(identifier: String) {
        val trimmed = identifier.trim()
        if (trimmed.isEmpty()) {
            _nativeAuth.update { state -> state.copy(error = "Enter your username, email or phone number") }
            return
        }
        viewModelScope.launch {
            _nativeAuth.update { state -> state.copy(busy = true, error = null, identifier = trimmed) }
            val result = runCatching { auth.checkIdentifier(trimmed) }.getOrNull()
            _nativeAuth.update { state ->
                when {
                    result == null -> state.copy(busy = false, error = "Could not reach Gothwad Accounts")
                    // Only a definite "does not exist" blocks the next step; an unknown answer
                    // still lets the user try the password.
                    result.exists == false && result.ok ->
                        state.copy(busy = false, error = "No Gothwad Account matches that identifier")
                    else -> state.copy(busy = false, step = NativeAuthStep.PASSWORD)
                }
            }
        }
    }

    fun nativeSubmitPassword(password: String) {
        val identifier = _nativeAuth.value.identifier
        if (password.isEmpty()) {
            _nativeAuth.update { state -> state.copy(error = "Enter your password") }
            return
        }
        viewModelScope.launch {
            _nativeAuth.update { state -> state.copy(busy = true, error = null) }
            when (val outcome = auth.signInNatively(identifier, password)) {
                is NativeAuthOutcome.Success -> finishNativeAuth(outcome.account.username)
                is NativeAuthOutcome.MfaRequired -> _nativeAuth.update { state ->
                    state.copy(
                        busy = false,
                        step = NativeAuthStep.MFA,
                        challenge = outcome.challenge,
                        hint = outcome.message ?: "Enter the 6-digit code from your authenticator app",
                    )
                }
                is NativeAuthOutcome.Failed -> _nativeAuth.update { state ->
                    state.copy(busy = false, error = nativeAuthFailureText(outcome))
                }
            }
        }
    }

    fun nativeSubmitMfa(code: String) {
        val challenge = _nativeAuth.value.challenge ?: return
        viewModelScope.launch {
            _nativeAuth.update { state -> state.copy(busy = true, error = null) }
            when (val outcome = auth.verifyMfa(challenge, code.trim())) {
                is NativeAuthOutcome.Success -> finishNativeAuth(outcome.account.username)
                is NativeAuthOutcome.MfaRequired -> _nativeAuth.update { state ->
                    state.copy(busy = false, challenge = outcome.challenge, error = "That code was not accepted")
                }
                is NativeAuthOutcome.Failed -> _nativeAuth.update { state ->
                    state.copy(busy = false, error = nativeAuthFailureText(outcome))
                }
            }
        }
    }

    fun nativeSubmitSignUp(request: ApiNativeSignUpRequest) {
        viewModelScope.launch {
            _nativeAuth.update { state -> state.copy(busy = true, error = null) }
            when (val outcome = auth.signUpNatively(request)) {
                is NativeAuthOutcome.Success -> finishNativeAuth(outcome.account.username)
                is NativeAuthOutcome.MfaRequired -> _nativeAuth.update { state ->
                    state.copy(busy = false, step = NativeAuthStep.MFA, challenge = outcome.challenge)
                }
                is NativeAuthOutcome.Failed -> _nativeAuth.update { state ->
                    state.copy(busy = false, error = nativeAuthFailureText(outcome))
                }
            }
        }
    }

    fun nativeForgotPassword(username: String, onSent: (String) -> Unit = {}) {
        viewModelScope.launch {
            _nativeAuth.update { state -> state.copy(busy = true, error = null) }
            val result = runCatching { auth.forgotPassword(username) }.getOrNull()
            _nativeAuth.update { state ->
                state.copy(
                    busy = false,
                    error = if (result?.ok == true) null else result?.error ?: "Could not send the reset link",
                    hint = if (result?.ok == true) {
                        result.message ?: "Check your recovery email for the reset link"
                    } else {
                        state.hint
                    },
                )
            }
            if (result?.ok == true) onSent(result.message ?: "Check your recovery email for the reset link")
        }
    }

    fun nativeResetPassword(token: String, password: String) {
        viewModelScope.launch {
            _nativeAuth.update { state -> state.copy(busy = true, error = null) }
            val result = runCatching { auth.resetPassword(token, password) }.getOrNull()
            _nativeAuth.update { state ->
                state.copy(
                    busy = false,
                    error = if (result?.ok == true) null else result?.error ?: "Could not reset the password",
                    step = if (result?.ok == true) NativeAuthStep.IDENTIFIER else state.step,
                    hint = if (result?.ok == true) "Password updated. Sign in with your new password." else state.hint,
                )
            }
        }
    }

    private fun finishNativeAuth(username: String) {
        _nativeAuth.update { state ->
            state.copy(busy = false, visible = false, step = NativeAuthStep.IDENTIFIER)
        }
        viewModelScope.launch {
            repository.activeAccount()?.let { refreshCurrentFolder(showSpinner = true) }
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Signed in as $username"))
        }
    }

    private fun nativeAuthFailureText(outcome: NativeAuthOutcome.Failed): String = when {
        outcome.rejected -> "Incorrect identifier or password"
        outcome.message.contains("mfa", ignoreCase = true) -> "Enter the 6-digit code from your authenticator app"
        else -> outcome.message.ifBlank { "Could not sign in" }
    }

    /** Starts the interactive Gothwad Accounts sign-in from the signed-out screen. */
    fun signIn(activity: android.app.Activity, chooseAccount: Boolean = false) {
        auth.signIn(activity, chooseAccount = chooseAccount)
    }

    /** One-tap sign-in for an account discovered on this device. */
    fun signInAsDiscovered(activity: android.app.Activity, discovered: DiscoveredAccount) {
        auth.signInAsDiscovered(activity, discovered)
    }

    fun openAccountManager(activity: android.app.Activity) = auth.openAccountManager(activity)

    fun refreshDiscoveredAccounts() = auth.refreshDiscoveredAccounts()

    fun clearAuthFailure() = auth.sessionManager.clearFailure()

    fun signOutOfMail(account: MailAccountEntity) {
        viewModelScope.launch {
            val authAccount = auth.sessionManager.accounts().find { it.email == account.email }
            if (authAccount == null) {
                repository.removeAccount(account.email)
            } else {
                auth.signOutOfMail(authAccount)
            }
        }
    }

    fun disconnectFromServer(account: MailAccountEntity) {
        viewModelScope.launch {
            val authAccount = auth.sessionManager.accounts().find { it.email == account.email }
            if (authAccount == null) {
                repository.removeAccount(account.email)
            } else {
                auth.disconnectFromServer(authAccount)
            }
        }
    }

    fun removeAccountFromDevice(account: MailAccountEntity) {
        viewModelScope.launch {
            val authAccount = auth.sessionManager.accounts().find { it.email == account.email }
            if (authAccount == null) {
                repository.removeAccount(account.email)
            } else {
                auth.removeAccountFromDevice(authAccount)
            }
        }
    }

    // --- Navigation ---------------------------------------------------------

    fun observeThread(threadId: String): Flow<EmailThreadEntity?> = repository.observeThread(threadId)

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
        refreshCurrentFolder(showSpinner = true)
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

    fun selectTabletThread(threadId: String?) {
        localState.update { it.copy(activeTabletThreadId = threadId) }
        if (threadId != null) {
            openThread(threadId)
        }
    }

    fun clearTabletThread() {
        localState.update { it.copy(activeTabletThreadId = null) }
    }

    // --- Sync ---------------------------------------------------------------

    /** Pull-to-refresh and manual retry entry point. */
    fun refreshInbox() {
        refreshCurrentFolder(showSpinner = true)
    }

    fun refreshCurrentFolder(showSpinner: Boolean = false) {
        val account = uiState.value.activeAccount ?: return
        val folder = localState.value.selectedFolder
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            if (showSpinner) localState.update { it.copy(isRefreshing = true) }
            try {
                repository.refreshCurrentFolder(account, folder)
                lastSyncedFolder = folder
                auth.refreshDiscoveredAccounts()
                localState.update { it.copy(syncError = null) }
            } catch (error: MailApiException) {
                if (error.isUnauthenticated) {
                    localState.update { it.copy(syncError = "Your Gothwad Account session expired") }
                } else {
                    localState.update { it.copy(syncError = "Could not sync Gothwad Mail") }
                }
            } catch (error: Exception) {
                localState.update { it.copy(syncError = "Could not sync Gothwad Mail") }
            } finally {
                if (showSpinner) localState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    // --- Selection ----------------------------------------------------------

    fun toggleThreadSelection(threadId: String) {
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

    // --- Thread actions -----------------------------------------------------

    /** Opens a thread: marks it read and downloads the body on first open. */
    fun openThread(threadId: String) {
        viewModelScope.launch {
            val account = uiState.value.activeAccount ?: return@launch
            val thread = repository.getThread(threadId) ?: return@launch
            runCatching { repository.openThread(account, thread) }
                .onFailure { _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Could not open this message")) }
        }
    }

    fun markThreadRead(threadId: String, isRead: Boolean) {
        viewModelScope.launch {
            val account = uiState.value.activeAccount ?: return@launch
            val thread = repository.getThread(threadId) ?: return@launch
            runCatching { repository.markRead(account, listOf(thread), isRead) }
        }
    }

    fun markSelectedRead(isRead: Boolean) {
        val selected = uiState.value.visibleThreads.filter { it.id in localState.value.selectedThreadIds }
        localState.update { it.copy(selectedThreadIds = emptySet()) }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val account = uiState.value.activeAccount ?: return@launch
            runCatching { repository.markRead(account, selected, isRead) }
            _snackbarEvents.emit(
                MailSnackbarEvent.SimpleMessage(
                    if (isRead) "${selected.size} marked as read" else "${selected.size} marked as unread"
                )
            )
        }
    }

    fun toggleStar(thread: EmailThreadEntity) {
        viewModelScope.launch {
            val account = uiState.value.activeAccount ?: return@launch
            runCatching { repository.toggleStar(account, thread) }
        }
    }

    fun toggleImportant(thread: EmailThreadEntity) {
        viewModelScope.launch {
            val account = uiState.value.activeAccount ?: return@launch
            runCatching { repository.toggleImportant(account, thread) }
        }
    }

    fun handleSwipe(thread: EmailThreadEntity, action: SwipeAction) {
        viewModelScope.launch {
            when (action) {
                SwipeAction.ARCHIVE -> archiveThreads(listOf(thread))
                SwipeAction.DELETE -> trashThreads(listOf(thread))
                SwipeAction.MARK_READ -> {
                    val account = uiState.value.activeAccount ?: return@launch
                    val nextRead = !thread.isRead
                    runCatching { repository.markRead(account, listOf(thread), nextRead) }
                    _snackbarEvents.emit(
                        MailSnackbarEvent.SimpleMessage(
                            if (nextRead) "Marked as read" else "Marked as unread"
                        )
                    )
                }
            }
        }
    }

    /**
     * The Worker has no archive mailbox: archiving moves the message out of the inbox, which is the
     * same server operation as moving it to Trash.
     */
    fun archiveSelected() {
        val selected = uiState.value.visibleThreads.filter { it.id in localState.value.selectedThreadIds }
        localState.update { it.copy(selectedThreadIds = emptySet()) }
        archiveThreads(selected)
    }

    fun archiveThreads(threads: List<EmailThreadEntity>) {
        trashThreads(threads, reason = "Archived")
    }

    fun trashSelected() {
        val selected = uiState.value.visibleThreads.filter { it.id in localState.value.selectedThreadIds }
        localState.update { it.copy(selectedThreadIds = emptySet()) }
        trashThreads(selected)
    }

    fun trashThreads(threads: List<EmailThreadEntity>, reason: String? = null) {
        if (threads.isEmpty()) return
        viewModelScope.launch {
            val account = uiState.value.activeAccount ?: return@launch
            val inTrash = threads.all { it.folder == MailFolder.TRASH }
            if (inTrash) {
                runCatching { repository.deleteForever(account, threads) }
                _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Deleted forever"))
            } else {
                runCatching { repository.moveToTrash(account, threads) }
                _snackbarEvents.emit(
                    MailSnackbarEvent.MessageWithUndo(
                        message = reason
                            ?: if (threads.size == 1) "Moved to Trash" else "${threads.size} moved to Trash",
                        affectedThreads = threads
                    )
                )
            }
            if (localState.value.activeTabletThreadId in threads.map { it.id }) {
                localState.update { it.copy(activeTabletThreadId = null) }
            }
        }
    }

    /** Undo for a swipe or toolbar delete: restores the messages to their previous folder. */
    fun undoFolderMove(threads: List<EmailThreadEntity>) {
        if (threads.isEmpty()) return
        viewModelScope.launch {
            val account = uiState.value.activeAccount ?: return@launch
            runCatching { repository.restoreFromTrash(account, threads) }
        }
    }

    fun reportSpam(thread: EmailThreadEntity) {
        viewModelScope.launch {
            val account = uiState.value.activeAccount ?: return@launch
            runCatching { repository.moveToTrash(account, listOf(thread)) }
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Reported as spam"))
        }
    }

    fun moveThreadToFolder(thread: EmailThreadEntity, folder: MailFolder) {
        if (folder == MailFolder.TRASH) {
            trashThreads(listOf(thread))
            return
        }
        viewModelScope.launch {
            val account = uiState.value.activeAccount ?: return@launch
            runCatching { repository.restoreFromTrash(account, listOf(thread)) }
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Moved to ${folder.displayName}"))
        }
    }

    fun snoozeThread(thread: EmailThreadEntity, untilLabel: String) {
        _snackbarEvents.tryEmit(
            MailSnackbarEvent.SimpleMessage("Snooze is not available for Gothwad Mail yet — $untilLabel"),
        )
    }

    fun updateLabels(thread: EmailThreadEntity, labels: List<String>) {
        _snackbarEvents.tryEmit(MailSnackbarEvent.SimpleMessage("Labels are not available for Gothwad Mail yet"))
    }

    /** `DELETE /api/mail/trash` for the active account. */
    fun emptyCurrentFolder() {
        val state = uiState.value
        if (state.selectedFolder != MailFolder.TRASH) return
        val account = state.activeAccount ?: return
        viewModelScope.launch {
            val deleted = runCatching { repository.emptyTrash(account) }.getOrElse { error ->
                _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Could not empty Trash"))
                return@launch
            }
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Trash emptied ($deleted messages)"))
        }
    }

    // --- Accounts & settings -----------------------------------------------

    fun switchAccount(email: String) {
        viewModelScope.launch {
            val target = repository.accountsSnapshot().find { it.email == email } ?: return@launch
            val userId = auth.sessionManager.accounts().find { it.email == email }?.userId
            if (userId != null) auth.sessionManager.switchTo(userId)
            repository.switchAccount(email)
            lastSyncedAccount = email
            localState.update { it.copy(selectedThreadIds = emptySet(), activeTabletThreadId = null) }
            refreshCurrentFolder(showSpinner = true)
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Switched to ${target.displayName}"))
        }
    }

    /**
     * "Add another account" always goes through Gothwad Accounts. There is no local text-field
     * account form: a Mail account exists only with a real OAuth grant.
     */
    fun addAccount(activity: android.app.Activity) {
        auth.signIn(activity, chooseAccount = true)
    }

    fun updateAccountSettings(account: MailAccountEntity) {
        viewModelScope.launch {
            repository.updateAccount(account)
            _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Account preferences saved"))
        }
    }

    fun updateRetention(policy: RetentionPolicy) {
        val account = uiState.value.activeAccount ?: return
        viewModelScope.launch {
            val result = runCatching { repository.updateRetention(account, policy) }
            result.onSuccess {
                _snackbarEvents.emit(
                    MailSnackbarEvent.SimpleMessage("Mailbox retention set to ${policy.label.lowercase()}"),
                )
            }.onFailure {
                _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Could not update mailbox retention"))
            }
        }
    }

    fun updateSettings(settings: AppSettingsEntity) {
        viewModelScope.launch { repository.updateSettings(settings) }
    }

    fun updateTheme(themePreference: ThemePreference) {
        val current = uiState.value.settings
        updateSettings(current.copy(themePreference = themePreference))
    }

    // --- Search -------------------------------------------------------------

    fun updateSearchQuery(query: String) {
        localState.update { it.copy(searchState = it.searchState.copy(query = query)) }
        scheduleServerSearch()
    }

    /** Debounced server search merged with the instant local filter. */
    private fun scheduleServerSearch() {
        searchJob?.cancel()
        val snapshot = localState.value
        val query = snapshot.searchState.query.trim()
        val filters = snapshot.searchState
        if (query.isEmpty() && !filters.onlyUnread && !filters.onlyStarred && !filters.onlyHasAttachment) {
            localState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            localState.update { it.copy(isSearching = true) }
            delay(SEARCH_DEBOUNCE_MILLIS)
            val account = uiState.value.activeAccount ?: return@launch
            runCatching {
                repository.search(
                    account = account,
                    query = query,
                    folder = if (filters.onlySentByMe) MailFolder.SENT else MailFolder.ALL_MAIL,
                    unreadOnly = filters.onlyUnread,
                    starredOnly = filters.onlyStarred,
                    hasAttachment = filters.onlyHasAttachment,
                )
            }.onSuccess { results ->
                localState.update { it.copy(searchResults = results, isSearching = false) }
            }.onFailure {
                localState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            }
        }
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
        scheduleServerSearch()
    }

    fun resetSearch() {
        searchJob?.cancel()
        localState.update { state ->
            state.copy(
                searchState = SearchFilterState(recentSearches = state.searchState.recentSearches),
                searchResults = emptyList(),
                isSearching = false
            )
        }
    }

    // --- Compose ------------------------------------------------------------

    /**
     * Sends through the Worker. `draftId` and `replyToThreadId` carry the server message ids, so the
     * server consumes the exact draft the user edited.
     */
    fun sendOrScheduleEmail(
        draftId: String?,
        replyToThreadId: String?,
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
            if (scheduledForMillis != null) {
                _snackbarEvents.emit(
                    MailSnackbarEvent.SimpleMessage("Scheduled send is not available for Gothwad Mail yet"),
                )
                return@launch
            }
            if (to.isEmpty()) {
                _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Add at least one recipient"))
                return@launch
            }
            val result = runCatching {
                repository.send(
                    account = fromAccount,
                    to = to,
                    cc = cc,
                    subject = subject,
                    body = body,
                    draftId = draftId,
                )
            }
            result.onSuccess { sendResult ->
                val remaining = sendResult.remainingToday
                _snackbarEvents.emit(
                    MailSnackbarEvent.SimpleMessage(
                        if (remaining != null) "Message sent — $remaining remaining today" else "Message sent",
                    ),
                )
            }.onFailure { error ->
                _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage(sendFailureMessage(error)))
            }
        }
    }

    /** Auto-save: persists the draft on the server so it survives reinstalls. */
    fun saveDraft(
        draftId: String?,
        fromAccount: MailAccountEntity,
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String,
        attachments: List<AttachmentItem>
    ) {
        if (to.isEmpty() && subject.isBlank() && body.isBlank() && attachments.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                repository.saveDraft(
                    account = fromAccount,
                    serverDraftId = draftId,
                    to = to,
                    cc = cc,
                    subject = subject,
                    body = body,
                )
            }.onFailure {
                _snackbarEvents.emit(MailSnackbarEvent.SimpleMessage("Could not save the draft on the server"))
            }
        }
    }

    fun deleteDraft(thread: EmailThreadEntity) {
        viewModelScope.launch {
            val account = uiState.value.activeAccount ?: return@launch
            runCatching { repository.deleteDraft(account, thread) }
        }
    }

    fun buildComposeForReply(
        thread: EmailThreadEntity,
        replyAll: Boolean,
        forward: Boolean
    ): ComposeInitialData {
        val lastMessage = thread.messages.lastOrNull()
        val activeEmail = uiState.value.activeAccount?.email.orEmpty()
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
            initialBody = thread.bodyText.ifBlank { msg?.body.orEmpty() },
            initialAttachments = msg?.attachments ?: emptyList()
        )
    }

    // --- Meet ---------------------------------------------------------------

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

    // --- Filtering ----------------------------------------------------------

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
                thread.bodyText.contains(q, ignoreCase = true) ||
                thread.messages.any { it.body.contains(q, ignoreCase = true) }

            val unreadOk = !search.onlyUnread || !thread.isRead
            val starredOk = !search.onlyStarred || thread.isStarred
            val attachOk = !search.onlyHasAttachment || thread.hasAttachments
            val sentOk = !search.onlySentByMe || thread.folder == MailFolder.SENT
            matchesQuery && unreadOk && starredOk && attachOk && sentOk
        }
    }

    class Factory(
        private val repository: MailRepository,
        private val auth: GothwadMailAuth,
        private val tokenProvider: MailAccessTokenProvider
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MailViewModel(repository, auth, tokenProvider) as T
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MILLIS: Long = 350L
    }
}
