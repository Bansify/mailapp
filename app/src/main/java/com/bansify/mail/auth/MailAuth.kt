package com.bansify.mail.auth

import android.app.Activity
import com.bansify.accounts.core.AccountState
import com.bansify.accounts.core.AuthFailure
import com.bansify.accounts.core.GothwadAccount
import com.bansify.accounts.core.GothwadAccounts
import com.bansify.accounts.core.MobileAuthSignInOutcome
import com.bansify.accounts.core.NativeSignUpInput
import com.bansify.accounts.ui.openAccountManagement
import com.bansify.mail.auth.broker.DiscoveredAccount
import com.bansify.mail.data.remote.MailAccessTokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Mail's thin facade over the shared Bansify Accounts SDK (`com.github.Bansify.accountsapp:core`).
 *
 * Bansify Email no longer ships its own PKCE client, encrypted token store, callback activity or
 * peer broker. The SDK owns credentials, refresh, cross-app discovery and the account centre, so the
 * sign-in experience is identical in every Bansify app. This class only translates SDK state into
 * the model types the Mail UI already renders.
 */
class MailAuth : MailAccessTokenProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Snapshot access used by the ViewModel for account bookkeeping. */
    inner class SessionBridge {

        suspend fun accounts(): List<MailAuthAccount> =
            signedInAccounts().map { it.toMailAccount() }

        fun currentSession(): MailAuthSession {
            val accounts = signedInAccounts().map { it.toMailAccount() }
            return MailAuthSession(
                credentials = accounts.map { account ->
                    // Tokens never leave the SDK; the UI only needs the identity here.
                    MailCredential(account = account, accessToken = "")
                },
                activeUserId = activeAccount()?.userId,
            )
        }

        fun switchTo(userId: String) {
            GothwadAccounts.switchTo(userId)
        }

        fun clearFailure() {
            GothwadAccounts.clearError()
        }

        val failure: StateFlow<MailAuthFailure?> by lazy {
            GothwadAccounts.error
                .map { failure -> failure?.toMailFailure() }
                .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = null)
        }
    }

    val sessionManager = SessionBridge()

    /** Signed-in accounts, mapped to the UI model. */
    val state: StateFlow<AuthSessionState> by lazy {
        GothwadAccounts.state
        .map { sdkState ->
            when (sdkState) {
                is AccountState.Initializing -> AuthSessionState.Initializing
                is AccountState.SignedOut -> AuthSessionState.SignedOut
                is AccountState.StorageCorrupted -> AuthSessionState.StorageCorrupted(sdkState.reason)
                is AccountState.SignedIn -> AuthSessionState.SignedIn(
                    accounts = sdkState.all.map { it.toMailAccount() },
                    activeAccount = sdkState.account.toMailAccount(),
                )
            }
        }
        .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = AuthSessionState.Initializing)
    }

    /** Device-wide, zero-token account directory (local accounts + verified sibling apps). */
    val discoveredAccounts: StateFlow<List<DiscoveredAccount>> by lazy {
        GothwadAccounts.discoveredAccounts
            .map { peers -> peers.map { it.toMailDiscoveredAccount() } }
            .stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = emptyList())
    }

    private val mutableEvents = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AuthEvent> = mutableEvents.asSharedFlow()

    var lastSilentFailureReason: String? = null
        private set

    fun initialize() {
        // The SDK is initialized once from MailApplication.onCreate; nothing to load here.
    }

    override suspend fun accessToken(): String? = GothwadAccounts.currentAccessToken()

    fun signIn(activity: Activity, chooseAccount: Boolean = false, loginHint: String? = null) {
        GothwadAccounts.signInWithBrowser(activity, chooseAccount = chooseAccount, loginHint = loginHint)
    }

    fun openAccountManager(activity: Activity) {
        GothwadAccounts.openAccountManagement(activity)
    }

    fun refreshDiscoveredAccounts() {
        GothwadAccounts.syncDiscoveredAccounts()
    }

    suspend fun signInSilently(discovered: DiscoveredAccount): MailAuthAccount? {
        val peer = GothwadAccounts.discoveredAccounts.value.firstOrNull { it.userId == discovered.userId }
        if (peer == null) {
            lastSilentFailureReason = "peer_not_found"
            return null
        }
        return runCatching { GothwadAccounts.signInSilentlyWithPeer(peer) }
            .fold(
                onSuccess = { result -> result.getOrNull()?.toMailAccount() ?: activeAccount()?.toMailAccount() },
                onFailure = { error ->
                    lastSilentFailureReason = error.message ?: "silent_sign_in_failed"
                    null
                },
            )
    }

    suspend fun signInAllSilently(): Boolean {
        val peers = GothwadAccounts.discoveredAccounts.value.filter { !it.isAuthorizedLocally }
        for (peer in peers) {
            val ok = runCatching { GothwadAccounts.signInSilentlyWithPeer(peer).isSuccess }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    fun signInAsDiscovered(activity: Activity, discovered: DiscoveredAccount) {
        // A verified peer account exists on this device; the shared browser flow reuses it through
        // the login hint, and the shared sign-in screen is the fallback when the peer is gone.
        GothwadAccounts.signInWithBrowser(activity, chooseAccount = false, loginHint = discovered.email)
    }

    fun signOutOfMail(account: MailAuthAccount) {
        GothwadAccounts.signOut(account.userId)
    }

    suspend fun disconnectFromServer(account: MailAuthAccount) {
        GothwadAccounts.disconnectFromServer(account.userId)
    }

    fun removeAccountFromDevice(account: MailAuthAccount) {
        GothwadAccounts.removeFromDevice(account.userId)
    }

    fun describeSilentFailure(reason: String?): String = when (reason) {
        null -> ""
        "peer_not_found" -> "That account is no longer available on this device."
        else -> "Could not sign in silently. Please sign in again."
    }

    // ---------------------------------------------------------------------------------------
    // Native (browser-less) credential flow, backed by the shared SDK.
    // ---------------------------------------------------------------------------------------

    suspend fun checkIdentifier(identifier: String): ApiCheckIdentifierResult =
        GothwadAccounts.checkIdentifier(identifier).fold(
            onSuccess = { result ->
                ApiCheckIdentifierResult(ok = result.error == null, exists = result.exists, kind = result.kind, error = result.error)
            },
            onFailure = { error -> ApiCheckIdentifierResult(ok = false, error = error.message) },
        )

    suspend fun checkUsername(username: String): ApiCheckUsernameResult =
        GothwadAccounts.checkUsername(username).fold(
            onSuccess = { result ->
                ApiCheckUsernameResult(
                    ok = result.error == null,
                    available = result.available,
                    valid = result.valid,
                    email = result.email,
                    error = result.error,
                )
            },
            onFailure = { error -> ApiCheckUsernameResult(ok = false, error = error.message) },
        )

    suspend fun signInNatively(identifier: String, password: String): NativeAuthOutcome =
        GothwadAccounts.signInNative(identifier, password).fold(
            onSuccess = { outcome ->
                when (outcome) {
                    is MobileAuthSignInOutcome.Authenticated -> signedInOutcome()
                    is MobileAuthSignInOutcome.MfaRequired -> NativeAuthOutcome.MfaRequired(
                        challenge = outcome.challenge,
                        expiresIn = outcome.expiresIn.toInt(),
                        message = outcome.message,
                    )
                }
            },
            onFailure = { error -> NativeAuthOutcome.Failed(error.message ?: "Sign-in failed", rejected = false) },
        )

    suspend fun verifyMfa(challenge: String, code: String): NativeAuthOutcome =
        GothwadAccounts.verifyNativeMfa(challenge, code).fold(
            onSuccess = { account -> NativeAuthOutcome.Success(account.toMailAccount()) },
            onFailure = { error -> NativeAuthOutcome.Failed(error.message ?: "That code did not work", rejected = true) },
        )

    suspend fun signUpNatively(request: ApiNativeSignUpRequest): NativeAuthOutcome =
        GothwadAccounts.signUpNative(
            NativeSignUpInput(
                firstName = request.firstName,
                lastName = request.lastName,
                username = request.username,
                password = request.password,
                recoveryEmail = request.recoveryEmail.orEmpty(),
                phoneNumber = request.phoneNumber.orEmpty(),
                dateOfBirth = request.dateOfBirth.orEmpty(),
                gender = request.gender.orEmpty(),
            ),
        ).fold(
            onSuccess = { account -> NativeAuthOutcome.Success(account.toMailAccount()) },
            onFailure = { error -> NativeAuthOutcome.Failed(error.message ?: "Could not create the account", rejected = false) },
        )

    suspend fun forgotPassword(username: String): ApiNativeOkResult =
        GothwadAccounts.forgotPasswordNative(username).fold(
            onSuccess = { message -> ApiNativeOkResult(ok = true, message = message) },
            onFailure = { error -> ApiNativeOkResult(ok = false, error = error.message) },
        )

    suspend fun resetPassword(token: String, password: String): ApiNativeOkResult =
        GothwadAccounts.resetPasswordNative(token, password).fold(
            onSuccess = { message -> ApiNativeOkResult(ok = true, message = message) },
            onFailure = { error -> ApiNativeOkResult(ok = false, error = error.message) },
        )

    private fun signedInOutcome(): NativeAuthOutcome {
        val account = activeAccount()
            ?: return NativeAuthOutcome.Failed("Signed in, but the account details could not be read", rejected = false)
        return NativeAuthOutcome.Success(account.toMailAccount())
    }

    private fun signedInAccounts(): List<GothwadAccount> =
        (GothwadAccounts.state.value as? AccountState.SignedIn)?.all.orEmpty()

    private fun activeAccount(): GothwadAccount? =
        (GothwadAccounts.state.value as? AccountState.SignedIn)?.account
}

internal fun AuthFailure.toMailFailure(): MailAuthFailure = when (this) {
    AuthFailure.BrowserUnavailable -> MailAuthFailure.BrowserUnavailable
    AuthFailure.InvalidCallback -> MailAuthFailure.InvalidCallback
    AuthFailure.LoginCancelled -> MailAuthFailure.LoginCancelled
    AuthFailure.SessionExpired -> MailAuthFailure.SessionExpired
    AuthFailure.StorageUnavailable -> MailAuthFailure.StorageUnavailable
    AuthFailure.Unavailable -> MailAuthFailure.Unavailable
}

internal fun GothwadAccount.toMailAccount(): MailAuthAccount = MailAuthAccount(
    userId = userId,
    username = username.orEmpty(),
    email = email.orEmpty(),
    displayName = name ?: username.orEmpty(),
    avatarUrl = avatarUrl,
)

internal fun com.bansify.accounts.core.DiscoveredAccount.toMailDiscoveredAccount(): DiscoveredAccount =
    DiscoveredAccount(
        userId = userId,
        username = username,
        name = name,
        email = email,
        avatarUrl = avatarUrl,
        sourcePackage = sourcePackage,
        addedAt = addedAt,
        lastSeenAt = lastSeenAt,
        isAuthorizedLocally = isAuthorizedLocally,
    )
