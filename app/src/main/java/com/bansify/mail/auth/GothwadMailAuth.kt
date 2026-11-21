package com.bansify.mail.auth

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.bansify.mail.BuildConfig
import com.bansify.mail.auth.broker.AccountDirectoryProvider
import com.bansify.mail.auth.broker.AndroidPeerDirectoryTransport
import com.bansify.mail.auth.broker.BrokerPolicy
import com.bansify.mail.auth.broker.BrokerTrustPolicy
import com.bansify.mail.auth.broker.DirectorySnapshot
import com.bansify.mail.auth.broker.DirectoryHost
import com.bansify.mail.auth.broker.DiscoveredAccount
import com.bansify.mail.auth.broker.KnownGothwadApps
import com.bansify.mail.auth.broker.PeerAuthCode
import com.bansify.mail.auth.broker.PeerAuthCodeResult
import com.bansify.mail.auth.broker.PeerBrokerClient
import com.bansify.mail.data.remote.MailAccessTokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Events the UI reacts to, e.g. showing a snackbar after a one-tap sign-in attempt. */
sealed interface AuthEvent {
    data class SignedIn(val account: MailAuthAccount) : AuthEvent
    data class SignedOut(val account: MailAuthAccount?, val outcome: DisconnectOutcome) : AuthEvent
    data object LoginCancelled : AuthEvent
    data object BrowserUnavailable : AuthEvent

    /** A zero-browser peer sign-in finished; the account is already persisted. */
    data class SilentSignInSucceeded(val account: MailAuthAccount, val sourcePackage: String) : AuthEvent

    /** The silent attempt did not succeed, so the UI should offer an interactive sign-in. */
    data class SilentSignInFailed(val sourcePackage: String?, val reason: String) : AuthEvent
    data class Failed(val failure: MailAuthFailure) : AuthEvent
}

/**
 * Application-wide Bansify auth facade.
 *
 * Owns the encrypted multi-account session, the Custom Tabs sign-in flow, the peer directory
 * provider/client pair, and supplies bearer tokens to [MailApiClient].
 */
class GothwadMailAuth(
    private val application: Application,
    config: AuthConfig = AuthConfig.DEFAULT,
    private val oauth: MailOAuthService = MailOAuthClient(config),
    store: MailTokenStore = EncryptedTokenStore(application, config),
    private val nativeAuth: NativeAuthService = NativeAuthClient(config),
) : DirectoryHost, MailAccessTokenProvider {

    val config: AuthConfig = config

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val sessionManager: MailAuthSessionManager =
        MailAuthSessionManager(store = store, oauth = oauth, config = config)

    private val peerBroker: PeerBrokerClient = PeerBrokerClient(
        localPackage = application.packageName,
        transport = AndroidPeerDirectoryTransport(application),
    )

    private val mutableDiscovered = MutableStateFlow<List<DiscoveredAccount>>(emptyList())
    val discoveredAccounts: StateFlow<List<DiscoveredAccount>> = mutableDiscovered.asStateFlow()

    private val mutableEvents = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AuthEvent> = mutableEvents.asSharedFlow()

    /** Guards against a redirect being delivered twice (Custom Tabs + App Link). */
    private val callbackBudget = CallbackBudget()


    /**
     * Peer discovery is enabled only for verification-compatible builds: debug builds accept debug
     * siblings, release builds require the production release certificate.
     */
    override val brokerPolicy: BrokerPolicy = BrokerPolicy.PeerDiscovery(
        BrokerTrustPolicy.SameSigningCertificate(
            knownPackages = KnownGothwadApps.FIRST_PARTY_PACKAGES,
            allowDebugBuild = BuildConfig.DEBUG,
        ),
    )

    val state: StateFlow<AuthSessionState> get() = sessionManager.state

    /** Initializes the encrypted store and the first peer discovery sweep. */
    fun initialize() {
        AccountDirectoryProvider.directoryHost = this
        sessionManager.onSessionChanged = { session ->
            scope.launch { refreshDiscoveredAccounts(session) }
        }
        scope.launch {
            sessionManager.load()
            refreshDiscoveredAccounts()
            attemptSilentSignInIfUseful()
        }
    }

    /**
     * Zero-browser automatic sign-in.
     *
     * When this app holds no session but a verified sibling Bansify app on the device is already
     * signed in, ask it for a one-time PKCE code and redeem it in-process. The user sees no browser
     * and no permission screen. Any failure is reported as an event so the UI falls back to the
     * native sign-in sheet.
     */
    suspend fun attemptSilentSignInIfUseful(): Boolean {
        if (sessionManager.currentSession().credentials.isNotEmpty()) return false
        return signInAllSilently()
    }

    /**
     * Copies **every** discovered peer account, not just the first one.
     *
     * A user with three accounts in the Bansify Accounts app expects all three to appear in Mail.
     * Each account needs its own one-time code, so they are requested in turn; one failing account
     * (for example because its session expired) must not stop the others.
     */
    suspend fun signInAllSilently(): Boolean {
        val candidates = mutableDiscovered.value.filter { it.sourcePackage.isNotBlank() && !it.isAuthorizedLocally }
        if (candidates.isEmpty()) return false
        // Accounts app is tried first, then the documented first-party order; within one peer the
        // accounts are copied in discovery order so the primary account stays first.
        val ordered = candidates.sortedBy { candidate ->
            when (candidate.sourcePackage) {
                KnownGothwadApps.ACCOUNTS -> 0
                KnownGothwadApps.DRIVE -> 1
                KnownGothwadApps.STORE -> 2
                KnownGothwadApps.TUBE -> 3
                else -> 4
            }
        }
        var signedIn = 0
        for (candidate in ordered) {
            if (signInSilently(candidate, refreshDiscovery = false) != null) signedIn++
        }
        if (signedIn > 0) refreshDiscoveredAccounts()
        return signedIn > 0
    }

    /** Reason the last silent attempt failed, for the sign-in sheet to show. */
    @Volatile
    var lastSilentFailureReason: String? = null
        private set

    /**
     * Explains a peer-code failure in the words a user can act on.
     *
     * `untrusted_caller` / `peer_code_unsupported` mean the sibling app is an older build (or was
     * signed with a different key), which is the most common real-world reason silent SSO is not
     * available.
     */
    fun describeSilentFailure(reason: String?): String = when (reason) {
        null, "" -> "Automatic sign-in was not available"
        "untrusted_caller" ->
            "The Bansify app on this device is not signed with the same key, so it cannot share the account"
        "peer_code_unsupported", "no_response" ->
            "The Bansify app on this device is too old to share the account automatically"
        "account_not_signed_in", "no_session" ->
            "The Bansify app on this device is signed out, so there is no account to share"
        "peer_discovery_disabled" -> "Account sharing between Bansify apps is turned off in this build"
        "local_account" -> "That account already belongs to this app"
        "peer_unreachable" -> "The Bansify app on this device did not respond"
        "exchange_failed" -> "Bansify Accounts rejected the shared sign-in"
        else -> "Automatic sign-in was not available ($reason)"
    }

    /** One silent attempt for a discovered account. Returns the account on success. */
    suspend fun signInSilently(
        discovered: DiscoveredAccount,
        refreshDiscovery: Boolean = true,
    ): MailAuthAccount? {
        val verifier = Pkce.createVerifier()
        val challenge = Pkce.challenge(verifier)
        val redirect = GothwadEndpoints.REDIRECT_URI_CUSTOM_SCHEME
        val peerCode = withContext(Dispatchers.IO) {
            runCatching {
                peerBroker.requestPeerAuthCode(
                    discovered = discovered,
                    targetClientId = config.clientId,
                    redirectUri = redirect,
                    scope = GothwadEndpoints.SCOPE_PARAMETER,
                    codeChallenge = challenge,
                    policy = brokerPolicy,
                )
            }.getOrElse { PeerAuthCodeResult.failure("peer_unreachable") }
        }
        val issued = peerCode.code
        if (issued == null) {
            val reason = peerCode.failureReason ?: "peer_code_failed"
            lastSilentFailureReason = reason
            mutableEvents.emit(AuthEvent.SilentSignInFailed(discovered.sourcePackage, reason))
            return null
        }
        return try {
            val account = withContext(Dispatchers.IO) {
                sessionManager.completePeerLogin(
                    code = issued.code,
                    codeVerifier = verifier,
                    redirectUri = issued.redirectUri.ifBlank { redirect },
                )
            }
            lastSilentFailureReason = null
            mutableEvents.emit(AuthEvent.SilentSignInSucceeded(account, discovered.sourcePackage))
            if (refreshDiscovery) refreshDiscoveredAccounts()
            account
        } catch (error: Exception) {
            val reason = (error as? MailOAuthException)?.message ?: "exchange_failed"
            lastSilentFailureReason = reason
            mutableEvents.emit(AuthEvent.SilentSignInFailed(discovered.sourcePackage, reason))
            null
        }
    }

    // --- Direct native auth (no browser required) -----------------------------

    /** `POST /api/mobile/auth/check-identifier`. */
    suspend fun checkIdentifier(identifier: String): ApiCheckIdentifierResult =
        withContext(Dispatchers.IO) { nativeAuth.checkIdentifier(identifier) }

    /** `POST /api/mobile/auth/check-username`. */
    suspend fun checkUsername(username: String): ApiCheckUsernameResult =
        withContext(Dispatchers.IO) { nativeAuth.checkUsername(username) }

    /**
     * Native password sign-in. On `mfa_required` the challenge is returned to the UI, which then
     * asks for the six-digit code and calls [verifyMfa].
     */
    suspend fun signInNatively(identifier: String, password: String): NativeAuthOutcome =
        nativeStep { nativeAuth.signIn(identifier, password) }

    /** Second factor for [signInNatively]. */
    suspend fun verifyMfa(challenge: String, code: String): NativeAuthOutcome =
        nativeStep { nativeAuth.verifyMfa(challenge, code) }

    /** Native account creation; signs the new account in on success. */
    suspend fun signUpNatively(request: ApiNativeSignUpRequest): NativeAuthOutcome =
        nativeStep { nativeAuth.signUp(request) }

    suspend fun forgotPassword(username: String): ApiNativeOkResult =
        withContext(Dispatchers.IO) { nativeAuth.forgotPassword(username) }

    suspend fun resetPassword(token: String, password: String): ApiNativeOkResult =
        withContext(Dispatchers.IO) { nativeAuth.resetPassword(token, password) }

    /** Runs one native auth call and persists the session when tokens come back. */
    private suspend fun nativeStep(call: suspend () -> NativeAuthResult): NativeAuthOutcome {
        val result = try {
            withContext(Dispatchers.IO) { call() }
        } catch (error: MailOAuthException) {
            sessionManager.reportFailure(
                if (error.rejected) MailAuthFailure.SessionExpired else MailAuthFailure.Unavailable,
            )
            return NativeAuthOutcome.Failed(
                message = (error.message ?: "Could not reach Bansify Accounts"),
                rejected = error.rejected,
            )
        } catch (error: Exception) {
            sessionManager.reportFailure(MailAuthFailure.Unavailable)
            return NativeAuthOutcome.Failed(
                message = error.message ?: "Could not reach Bansify Accounts",
                rejected = false,
            )
        }
        return when (result) {
            is NativeAuthResult.MfaRequired -> NativeAuthOutcome.MfaRequired(
                challenge = result.challenge,
                expiresIn = result.expiresIn,
                message = result.message,
            )

            is NativeAuthResult.Tokens -> {
                // Prefer the identity block from the sign-in response; fall back to the
                // authoritative /oauth/userinfo so the persisted profile is never guessed.
                val info: GothwadUserInfo = try {
                    withContext(Dispatchers.IO) {
                        result.user?.toUserInfo()?.takeIf { !it.username.isNullOrBlank() }
                            ?: oauth.userInfo(result.tokens.accessToken)
                    }
                } catch (error: Exception) {
                    return NativeAuthOutcome.Failed(
                        "Signed in, but the Bansify profile could not be loaded",
                        rejected = false,
                    )
                }
                val account = try {
                    sessionManager.completeNativeLogin(result.tokens, info)
                } catch (error: Exception) {
                    return NativeAuthOutcome.Failed("The account could not be stored on this device", false)
                }
                sessionManager.clearFailure()
                mutableEvents.emit(AuthEvent.SignedIn(account))
                refreshDiscoveredAccounts()
                NativeAuthOutcome.Success(account)
            }
        }
    }

    // --- Sign in -------------------------------------------------------------

    /**
     * Opens Bansify Accounts in Custom Tabs.
     *
     * @param chooseAccount opens `/signin?choose=1&next=...` so the user can pick another account.
     * @param loginHint pre-selects the account (used by the one-tap discovered accounts).
     */
    fun signIn(activity: Activity, chooseAccount: Boolean = false, loginHint: String? = null) {
        scope.launch {
            val outcome = runCatching {
                val pending = sessionManager.beginLogin(loginHint)
                val authorize = AuthorizeRequest.authorizeUrl(pending, loginHint, config)
                val destination = if (chooseAccount) {
                    AuthorizeRequest.chooseAccountUrl(authorize, config)
                } else {
                    authorize
                }
                withContext(Dispatchers.Main) {
                    try {
                        CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(destination))
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    } catch (_: SecurityException) {
                        false
                    }
                }
            }
            if (outcome.getOrNull() != true) {
                sessionManager.cancelLogin()
                mutableEvents.emit(AuthEvent.BrowserUnavailable)
            }
        }
    }

    /**
     * Fire-and-forget callback entry point for [AccountCallbackActivity].
     *
     * The work runs on the application-owned [scope], never on the calling activity's lifecycle,
     * because the callback activity finishes immediately after the redirect is handed over.
     */
    fun acceptCallback(callbackUrl: String) {
        scope.launch { handleCallback(callbackUrl) }
    }

    /** Completes a sign-in from the callback activity. */
    suspend fun handleCallback(callbackUrl: String) {
        if (!callbackBudget.tryAcquire(callbackUrl)) {
            mutableEvents.emit(AuthEvent.Failed(MailAuthFailure.CallbackExpired))
            return
        }
        try {
            val account = sessionManager.completeLogin(callbackUrl)
            mutableEvents.emit(AuthEvent.SignedIn(account))
            refreshDiscoveredAccounts()
        } catch (error: PendingLoginUnavailableException) {
            // The browser came back with a code but there is no usable pending login any more:
            // the attempt expired, was already consumed, or was superseded by another sign-in.
            mutableEvents.emit(AuthEvent.Failed(MailAuthFailure.CallbackExpired))
        } catch (error: LoginCancelledException) {
            mutableEvents.emit(AuthEvent.LoginCancelled)
        } catch (error: CallbackValidator.InvalidCallbackException) {
            mutableEvents.emit(AuthEvent.Failed(MailAuthFailure.InvalidCallback))
        } catch (error: InvalidCredentialException) {
            mutableEvents.emit(AuthEvent.Failed(MailAuthFailure.SessionExpired))
        } catch (error: MailOAuthException) {
            mutableEvents.emit(
                AuthEvent.Failed(if (error.rejected) MailAuthFailure.SessionExpired else MailAuthFailure.Unavailable),
            )
        } catch (error: Exception) {
            mutableEvents.emit(AuthEvent.Failed(MailAuthFailure.Unavailable))
        }
    }

    /** One-tap sign-in for an account discovered from a verified sibling Bansify app. */
    fun signInAsDiscovered(activity: Activity, discovered: DiscoveredAccount) {
        signIn(activity, chooseAccount = false, loginHint = discovered.username)
    }

    /** Opens `com.bansify.accounts` when installed, otherwise the Accounts web page. */
    fun openAccountManager(activity: Activity) {
        val launch = AndroidPeerDirectoryTransport.isAccountsAppInstalled(application)
        if (launch) {
            val intent = application.packageManager.getLaunchIntentForPackage(KnownGothwadApps.ACCOUNTS)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { application.startActivity(intent) }
                    .onSuccess { return }
            }
        }
        runCatching {
            CustomTabsIntent.Builder().build()
                .launchUrl(activity, Uri.parse(GothwadEndpoints.MANAGE_ACCOUNT_WEB_URL))
        }.onFailure { mutableEvents.tryEmit(AuthEvent.BrowserUnavailable) }
    }

    // --- Account actions -----------------------------------------------------

    fun switchAccount(userId: String) = scope.launch {
        runCatching { sessionManager.switchTo(userId) }
            .onFailure { mutableEvents.emit(AuthEvent.Failed(MailAuthFailure.Unavailable)) }
    }

    /** Signs out of Mail only; the server grant stays valid for other devices. */
    fun signOutOfMail(account: MailAuthAccount) = scope.launch {
        val outcome = sessionManager.signOut(account.userId, revokeOnServer = false)
        mutableEvents.emit(AuthEvent.SignedOut(account, outcome))
    }

    /** Disconnects the Mail grant on the Bansify Accounts server, then clears local tokens. */
    fun disconnectFromServer(account: MailAuthAccount) = scope.launch {
        val outcome = sessionManager.signOut(account.userId, revokeOnServer = true)
        mutableEvents.emit(AuthEvent.SignedOut(account, outcome))
    }

    /** Removes the account from this device and notifies verified sibling apps. */
    fun removeAccountFromDevice(account: MailAuthAccount) = scope.launch {
        val removedAt = sessionManager.removeFromDevice(account.userId)
        // Verified sibling apps drop their copy through the tombstone; failures stay silent.
        peerBroker.broadcastDeviceRemoval(account.userId, removedAt, brokerPolicy)
        refreshDiscoveredAccounts()
        mutableEvents.emit(AuthEvent.SignedOut(account, DisconnectOutcome.LocalOnly))
    }

    /** Re-reads the directory from this app and every verified peer. */
    fun refreshDiscoveredAccounts() = scope.launch { refreshDiscoveredAccountsInternal() }

    private suspend fun refreshDiscoveredAccounts(session: MailAuthSession) {
        val localSession = session
        val snapshot = buildSnapshot(localSession)
        val localIds = localSession.credentials.map { it.account.userId }.toSet()
        val discovered = withContext(Dispatchers.IO) {
            runCatching { peerBroker.discoverAccounts(snapshot, localIds, brokerPolicy) }.getOrDefault(emptyList())
        }
        mutableDiscovered.value = discovered
    }

    private suspend fun refreshDiscoveredAccountsInternal() {
        refreshDiscoveredAccounts(sessionManager.currentSession())
    }

    private suspend fun buildSnapshot(session: MailAuthSession): DirectorySnapshot = DirectorySnapshot(
        sourcePackage = application.packageName,
        accounts = session.credentials.map { credential ->
            com.bansify.mail.auth.broker.DirectoryAccountRecord(
                userId = credential.account.userId,
                username = credential.account.username,
                name = credential.account.displayName,
                email = credential.account.email,
                avatarUrl = credential.account.avatarUrl,
                addedAt = credential.account.addedAt,
                lastSeenAt = credential.account.lastSeenAt,
            )
        },
        tombstones = session.deviceRemovalTombstones,
    )

    // --- DirectoryHost (ContentProvider) -------------------------------------

    override fun directorySnapshot(): DirectorySnapshot = runBlocking {
        sessionManager.currentSession().let { buildSnapshot(it) }
    }

    /**
     * Issues a one-time PKCE code for a trusted sibling app (it never receives a token).
     *
     * The access token is refreshed when needed, then handed to Accounts which mints the code.
     */
    override fun issuePeerCode(
        userId: String?,
        username: String?,
        email: String?,
        targetClientId: String,
        redirectUri: String,
        scope: String,
        codeChallenge: String,
        codeChallengeMethod: String,
    ): PeerAuthCode? = runBlocking {
        if (codeChallengeMethod != "S256") return@runBlocking null
        val session = sessionManager.currentSession()
        val credential = when {
            userId != null -> session.credentialFor(userId)
            email != null -> session.credentials.firstOrNull { it.account.email == email }
            username != null -> session.credentials.firstOrNull { it.account.username == username }
            else -> session.activeCredential()
        } ?: return@runBlocking null
        val token = try {
            sessionManager.accessToken(credential.account.userId)
        } catch (error: Exception) {
            null
        } ?: return@runBlocking null
        val code = oauth.issuePeerAuthCode(
            peerAccessToken = token,
            targetClientId = targetClientId,
            redirectUri = redirectUri,
            scope = scope,
            codeChallenge = codeChallenge,
        ) ?: return@runBlocking null
        PeerAuthCode(code = code, redirectUri = redirectUri, scope = scope)
    }

    override fun applyPeerDeviceRemoval(userId: String, removedAt: Long) {
        runBlocking { sessionManager.applyPeerDeviceRemoval(userId, removedAt) }
    }

    // --- MailAccessTokenProvider --------------------------------------------

    override suspend fun accessToken(): String? = try {
        sessionManager.accessToken()
    } catch (error: InvalidCredentialException) {
        null
    }

    /** True when at least one account can produce a token right now. */
    suspend fun hasUsableSession(): Boolean = accessToken() != null
}
