package com.gothwad.mail.auth

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.gothwad.mail.BuildConfig
import com.gothwad.mail.auth.broker.AccountDirectoryProvider
import com.gothwad.mail.auth.broker.AndroidPeerDirectoryTransport
import com.gothwad.mail.auth.broker.BrokerPolicy
import com.gothwad.mail.auth.broker.BrokerTrustPolicy
import com.gothwad.mail.auth.broker.DirectorySnapshot
import com.gothwad.mail.auth.broker.DirectoryHost
import com.gothwad.mail.auth.broker.DiscoveredAccount
import com.gothwad.mail.auth.broker.KnownGothwadApps
import com.gothwad.mail.auth.broker.PeerBrokerClient
import com.gothwad.mail.data.remote.MailAccessTokenProvider
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
    data class Failed(val failure: MailAuthFailure) : AuthEvent
}

/**
 * Application-wide Gothwad auth facade.
 *
 * Owns the encrypted multi-account session, the Custom Tabs sign-in flow, the peer directory
 * provider/client pair, and supplies bearer tokens to [MailApiClient].
 */
class GothwadMailAuth(
    private val application: Application,
    config: AuthConfig = AuthConfig.DEFAULT,
    oauth: MailOAuthService = MailOAuthClient(config),
    store: MailTokenStore = EncryptedTokenStore(application, config),
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
        }
    }

    // --- Sign in -------------------------------------------------------------

    /**
     * Opens Gothwad Accounts in Custom Tabs.
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

    /** One-tap sign-in for an account discovered from a verified sibling Gothwad app. */
    fun signInAsDiscovered(activity: Activity, discovered: DiscoveredAccount) {
        signIn(activity, chooseAccount = false, loginHint = discovered.username)
    }

    /** Opens `com.gothwad.accounts` when installed, otherwise the Accounts web page. */
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

    /** Disconnects the Mail grant on the Gothwad Accounts server, then clears local tokens. */
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
            com.gothwad.mail.auth.broker.DirectoryAccountRecord(
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
