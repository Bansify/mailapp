package com.gothwad.mail.auth

import com.gothwad.mail.auth.broker.DirectoryAccountRecord
import com.gothwad.mail.auth.broker.DirectorySnapshot
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Observable sign-in state for the signed-out and account screens. */
sealed interface AuthSessionState {
    data object Initializing : AuthSessionState
    data object SignedOut : AuthSessionState
    data class SignedIn(val accounts: List<MailAuthAccount>, val activeAccount: MailAuthAccount?) : AuthSessionState
    data class StorageCorrupted(val reason: String) : AuthSessionState
}

/** Delta callback invoked after every successful mutation of the persisted session. */
interface SessionListener {
    fun onSessionChanged(session: MailAuthSession) {}
}

/**
 * Multi-account OAuth session manager for Gothwad Mail.
 *
 * Accounts are keyed by the immutable `userId` (`sub` of `/oauth/userinfo`). All mutations happen
 * under a single mutex and are persisted to the encrypted store, so process death cannot split
 * memory and disk.
 */
class MailAuthSessionManager(
    private val store: MailTokenStore,
    private val oauth: MailOAuthService,
    private val config: AuthConfig = AuthConfig.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    private var data: MailAuthSession = MailAuthSession()
    private var generation: Long = 0L
    private var corrupted: Boolean = false

    private val mutableState = MutableStateFlow<AuthSessionState>(AuthSessionState.Initializing)
    val state: StateFlow<AuthSessionState> = mutableState.asStateFlow()

    private val mutableFailure = MutableStateFlow<MailAuthFailure?>(null)
    val failure: StateFlow<MailAuthFailure?> = mutableFailure.asStateFlow()

    private val mutableDisconnectOutcome = MutableStateFlow<DisconnectOutcome?>(null)
    val lastDisconnectOutcome: StateFlow<DisconnectOutcome?> = mutableDisconnectOutcome.asStateFlow()

    /** Invoked after every persisted change; the application uses it to mirror peers and cleanup. */
    var onSessionChanged: ((MailAuthSession) -> Unit)? = null

    suspend fun load() {
        sessionMutex {
            try {
                data = store.read()
                corrupted = false
                persistState()
            } catch (error: Exception) {
                if (error is AuthStorageException) {
                    corrupted = true
                    mutableState.value = AuthSessionState.StorageCorrupted(
                        error.message ?: "The encrypted account store could not be unlocked",
                    )
                    mutableFailure.value = MailAuthFailure.StorageUnavailable
                } else {
                    throw error
                }
            }
        }
    }

    /** Wipes the encrypted store so a corrupted Keystore entry can be replaced. */
    suspend fun recoverCorruptedStorage() = sessionMutex {
        generation++
        store.clear()
        val empty = MailAuthSession()
        store.write(empty)
        data = empty
        corrupted = false
        mutableFailure.value = null
        persistState()
    }

    fun clearFailure() {
        mutableFailure.value = null
    }

    fun clearDisconnectOutcome() {
        mutableDisconnectOutcome.value = null
    }

    /** Starts a login attempt: fresh 24-byte state and 64-byte verifier, persisted. */
    suspend fun beginLogin(loginHint: String? = null): PendingLogin = sessionMutex {
        check(!corrupted) { "Account storage is corrupted; recover before signing in" }
        generation++
        val pending = PendingLogin(
            state = Pkce.createState(),
            verifier = Pkce.createVerifier(),
            redirectUri = config.redirectUri,
            createdAt = clock(),
            loginHint = loginHint?.trim()?.takeIf { it.isNotEmpty() },
        )
        save(data.copy(pending = pending))
        pending
    }

    /**
     * Returns the pending login when it exists and has not expired (10 minutes), without
     * consuming it. Used to render retry state on the signed-out screen.
     */
    suspend fun peekPendingLogin(): PendingLogin? = sessionMutex {
        val pending = data.pending ?: return@sessionMutex null
        if (clock() - pending.createdAt !in 0..PENDING_LOGIN_TTL_MILLIS) null else pending
    }

    /**
     * Validates the OAuth callback, consumes the pending login exactly once, then exchanges the
     * code and loads the profile. Replays fail because the pending login is cleared first.
     */
    suspend fun completeLogin(callbackUrl: String): MailAuthAccount {
        val (pending, params, epoch) = sessionMutex {
            check(!corrupted) { "Account storage is corrupted" }
            // No usable pending login: expired, already consumed, or superseded. This is a
            // distinct, user-explainable state, not "the user cancelled in the browser".
            val pending = data.pending ?: throw PendingLoginUnavailableException()
            val age = clock() - pending.createdAt
            if (age !in 0..PENDING_LOGIN_TTL_MILLIS) {
                save(data.copy(pending = null))
                throw PendingLoginUnavailableException()
            }
            // `pending.redirectUri` stays the value sent to the token endpoint (the App Link);
            // the callback may legitimately arrive over either registered delivery URI.
            val result = CallbackValidator.validate(
                callback = callbackUrl,
                redirectUri = pending.redirectUri,
                state = pending.state,
                acceptedRedirectUris = listOf(
                    pending.redirectUri,
                    GothwadEndpoints.REDIRECT_URI_CUSTOM_SCHEME,
                ),
            )
            // Consume the pending login before any network work so a replay is impossible.
            save(data.copy(pending = null))
            Triple(pending, result, generation)
        }
        if (params.isError) {
            mutableFailure.value = MailAuthFailure.LoginCancelled
            throw LoginCancelledException()
        }
        val code = params.code ?: throw LoginCancelledException()

        val tokens = try {
            oauth.exchangeAuthorizationCode(code, pending.verifier, pending.redirectUri)
        } catch (error: MailOAuthException) {
            mutableFailure.value = if (error.rejected) MailAuthFailure.SessionExpired else MailAuthFailure.Unavailable
            throw error
        }
        val info = oauth.userInfo(tokens.accessToken)
        val account = info.toMailAccount(clock(), previous = null)

        sessionMutex {
            if (generation != epoch) {
                throw java.io.IOException("Sign-in was superseded by another account action")
            }
            val previous = data.credentialFor(account.userId)
            val addedAt = previous?.account?.addedAt?.takeIf { it > 0L } ?: account.addedAt
            val credential = MailCredential(
                account = account.copy(addedAt = addedAt, lastSeenAt = clock()),
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: previous?.refreshToken,
                expiresAt = clock() + tokens.expiresIn * 1000,
                scopes = parseScopes(tokens.scope),
            )
            // Signing back in supersedes an older device-removal tombstone.
            val tombstones = data.deviceRemovalTombstones.toMutableMap().apply {
                val existing = this[account.userId]
                if (existing != null && clock() > existing) remove(account.userId)
            }
            save(
                data.copy(
                    credentials = data.credentials.filterNot { it.account.userId == account.userId } + credential,
                    activeUserId = account.userId,
                    pending = null,
                    deviceRemovalTombstones = tombstones,
                ),
            )
            mutableFailure.value = null
        }
        return account
    }

    /** Marks a login attempt as cancelled and drops the pending state. */
    suspend fun cancelLogin() = sessionMutex {
        if (data.pending != null) {
            save(data.copy(pending = null))
        }
        mutableFailure.value = MailAuthFailure.LoginCancelled
    }

    suspend fun switchTo(userId: String) = sessionMutex {
        check(!corrupted) { "Account storage is corrupted" }
        require(data.credentials.any { it.account.userId == userId }) { "Unknown Gothwad account" }
        val now = clock()
        val updated = data.credentials.map {
            if (it.account.userId == userId) {
                it.copy(account = it.account.copy(lastSeenAt = now))
            } else {
                it
            }
        }
        save(data.copy(credentials = updated, activeUserId = userId))
    }

    /**
     * Returns a valid access token for [userId] (default: the active account), refreshing inside a
     * per-account mutex so concurrent callers share a single refresh request.
     *
     * `invalid_grant` / `401 invalid_token` evicts the credential and rethrows so the UI can ask
     * the user to sign in again.
     */
    suspend fun accessToken(userId: String? = null): String? {
        val targetId = userId ?: mutex.withLock { data.activeUserId } ?: return null
        return lockFor(targetId).withLock {
            val original = mutex.withLock { data.credentialFor(targetId) } ?: return@withLock null
            val now = clock()
            if (original.expiresAt - now > MailCredential.TOKEN_REFRESH_SKEW_MILLIS) {
                touch(original)
                return@withLock original.accessToken
            }
            val refreshToken = original.refreshToken
                ?: run {
                    evictInvalidCredential(targetId, original)
                    throw InvalidCredentialException()
                }
            val tokens = try {
                oauth.refreshToken(refreshToken)
            } catch (error: MailOAuthException) {
                if (error.rejected) {
                    evictInvalidCredential(targetId, original)
                    throw InvalidCredentialException()
                }
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw MailOAuthException(false, "Could not reach Gothwad Accounts to refresh the session", error)
            }
            mutex.withLock {
                val current = data.credentialFor(targetId)
                if (current == null) return@withLock null
                // Tokens rotated by a concurrent refresh are never overwritten with stale values.
                if (current.accessToken != original.accessToken &&
                    current.expiresAt - clock() > MailCredential.TOKEN_REFRESH_SKEW_MILLIS
                ) {
                    return@withLock current.accessToken
                }
                val rotated = current.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken ?: current.refreshToken,
                    expiresAt = clock() + tokens.expiresIn * 1000,
                    scopes = parseScopes(tokens.scope).ifEmpty { current.scopes },
                )
                save(data.copy(credentials = data.credentials.map { if (it.account.userId == targetId) rotated else it }))
                rotated.accessToken
            }
        }
    }

    /**
     * Sign out of Mail only ([revokeOnServer] = false) or disconnect the Mail grant on the server
     * before clearing local tokens ([revokeOnServer] = true).
     */
    suspend fun signOut(userId: String, revokeOnServer: Boolean = false): DisconnectOutcome {
        val outcome = if (revokeOnServer) revokeGrant(userId) else DisconnectOutcome.LocalOnly
        removeLocalCredential(userId)
        if (revokeOnServer) mutableDisconnectOutcome.value = outcome
        return outcome
    }

    /**
     * Removes the account from this device, records a tombstone and returns the removal timestamp
     * so the caller can broadcast it to verified sibling Gothwad apps.
     */
    suspend fun removeFromDevice(
        userId: String,
        removedAt: Long = clock(),
        revokeOnServer: Boolean = false,
    ): Long {
        if (revokeOnServer) {
            val outcome = revokeGrant(userId)
            mutableDisconnectOutcome.value = outcome
        }
        sessionMutex {
            check(!corrupted) { "Account storage is corrupted" }
            generation++
            val remaining = data.credentials.filterNot { it.account.userId == userId }
            save(
                data.copy(
                    credentials = remaining,
                    activeUserId = if (data.activeUserId == userId) remaining.firstOrNull()?.account?.userId else data.activeUserId,
                    pending = null,
                    deviceRemovalTombstones = data.deviceRemovalTombstones + (userId to removedAt),
                ),
            )
        }
        return removedAt
    }

    /** Applies a device-removal tombstone received from a verified peer Gothwad app. */
    suspend fun applyPeerDeviceRemoval(userId: String, removedAt: Long) {
        if (userId.isBlank() || removedAt <= 0L) return
        sessionMutex {
            if (corrupted) return@sessionMutex
            val existing = data.deviceRemovalTombstones[userId] ?: 0L
            val effective = maxOf(existing, removedAt)
            val credential = data.credentialFor(userId)
            val shouldRemove = credential != null &&
                maxOf(credential.account.addedAt, credential.account.lastSeenAt) <= effective
            if (shouldRemove) generation++
            val remaining = if (shouldRemove) {
                data.credentials.filterNot { it.account.userId == userId }
            } else {
                data.credentials
            }
            val nextActive = if (shouldRemove && data.activeUserId == userId) {
                remaining.firstOrNull()?.account?.userId
            } else {
                data.activeUserId
            }
            save(
                data.copy(
                    credentials = remaining,
                    activeUserId = nextActive,
                    deviceRemovalTombstones = data.deviceRemovalTombstones + (userId to effective),
                ),
            )
        }
    }

    /** Refreshes the profile fields of every signed-in account from `/oauth/userinfo`. */
    suspend fun refreshProfiles() = sessionMutex {
        if (corrupted) return@sessionMutex
        var changed = false
        val updated = data.credentials.map { credential ->
            val token = runCatching { accessToken(credential.account.userId) }.getOrNull() ?: return@map credential
            val info = runCatching { oauth.userInfo(token) }.getOrNull() ?: return@map credential
            val merged = info.toMailAccount(clock(), previous = credential.account)
            changed = true
            credential.copy(account = merged.copy(lastSeenAt = clock()))
        }
        if (changed) save(data.copy(credentials = updated))
    }

    // --- Read-only accessors -------------------------------------------------

    suspend fun currentSession(): MailAuthSession = mutex.withLock { data }

    suspend fun accounts(): List<MailAuthAccount> = mutex.withLock { data.accounts() }

    suspend fun activeAccount(): MailAuthAccount? = mutex.withLock { data.activeCredential()?.account }

    suspend fun activeAccountId(): String? = mutex.withLock { data.activeUserId }

    /** Non-secret snapshot for sibling Gothwad apps. Tokens are never included. */
    suspend fun directorySnapshot(): DirectorySnapshot = mutex.withLock {
        if (corrupted) {
            DirectorySnapshot(sourcePackage = config.packageName)
        } else {
            DirectorySnapshot(
                sourcePackage = config.packageName,
                accounts = data.credentials.map { credential ->
                    DirectoryAccountRecord(
                        userId = credential.account.userId,
                        username = credential.account.username,
                        name = credential.account.displayName,
                        email = credential.account.email,
                        avatarUrl = credential.account.avatarUrl,
                        addedAt = credential.account.addedAt,
                        lastSeenAt = credential.account.lastSeenAt,
                    )
                },
                tombstones = data.deviceRemovalTombstones,
            )
        }
    }

    // --- Internals -----------------------------------------------------------

    private suspend fun revokeGrant(userId: String): DisconnectOutcome {
        val credential = mutex.withLock { data.credentialFor(userId) } ?: return DisconnectOutcome.AlreadyInvalidOnServer
        var bearer = credential.accessToken
        var refresh = credential.refreshToken
        if (credential.isExpiring(clock()) && !refresh.isNullOrBlank()) {
            val refreshed = runCatching { oauth.refreshToken(refresh) }
            refreshed.onSuccess {
                bearer = it.accessToken
                refresh = it.refreshToken ?: refresh
            }
            val error = refreshed.exceptionOrNull()
            if (error is MailOAuthException && error.rejected) return DisconnectOutcome.AlreadyInvalidOnServer
        }
        return when (oauth.revoke(refresh, bearer)) {
            RevokeStatus.Revoked -> DisconnectOutcome.RevokedOnServer
            RevokeStatus.InvalidGrant -> DisconnectOutcome.AlreadyInvalidOnServer
            RevokeStatus.Failed -> DisconnectOutcome.OfflineRemovedLocally
        }
    }

    private suspend fun evictInvalidCredential(userId: String, expected: MailCredential) = sessionMutex {
        val current = data.credentialFor(userId) ?: return@sessionMutex
        if (current.accessToken != expected.accessToken && current.refreshToken != expected.refreshToken) {
            // Another coroutine already rotated this credential; keep the newer one.
            return@sessionMutex
        }
        generation++
        val remaining = data.credentials.filterNot { it.account.userId == userId }
        save(
            data.copy(
                credentials = remaining,
                activeUserId = if (data.activeUserId == userId) remaining.firstOrNull()?.account?.userId else data.activeUserId,
            ),
        )
        mutableFailure.value = MailAuthFailure.SessionExpired
    }

    private suspend fun removeLocalCredential(userId: String) = sessionMutex {
        check(!corrupted) { "Account storage is corrupted" }
        generation++
        val remaining = data.credentials.filterNot { it.account.userId == userId }
        save(
            data.copy(
                credentials = remaining,
                activeUserId = if (data.activeUserId == userId) remaining.firstOrNull()?.account?.userId else data.activeUserId,
                pending = null,
            ),
        )
    }

    private suspend fun touch(credential: MailCredential) {
        val now = clock()
        if (now - credential.account.lastSeenAt < LAST_SEEN_TOUCH_INTERVAL_MILLIS) return
        sessionMutex {
            val current = data.credentialFor(credential.account.userId) ?: return@sessionMutex
            save(
                data.copy(
                    credentials = data.credentials.map {
                        if (it.account.userId == current.account.userId) {
                            it.copy(account = it.account.copy(lastSeenAt = now))
                        } else {
                            it
                        }
                    },
                ),
            )
        }
    }

    private fun lockFor(userId: String): Mutex = refreshLocks.getOrPut(userId) { Mutex() }

    private suspend fun <T> sessionMutex(block: suspend () -> T): T = mutex.withLock { block() }

    private fun save(next: MailAuthSession) {
        store.write(next)
        data = next
        publishState(next)
        onSessionChanged?.invoke(next)
    }

    private fun persistState() = publishState(data)

    private fun publishState(session: MailAuthSession) {
        val accounts = session.accounts()
        val active = session.activeCredential()?.account ?: accounts.firstOrNull()
        mutableState.value = if (accounts.isEmpty()) {
            AuthSessionState.SignedOut
        } else {
            AuthSessionState.SignedIn(accounts, active)
        }
    }

    private fun parseScopes(value: String?): List<String> =
        value?.split(' ')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun GothwadUserInfo.toMailAccount(now: Long, previous: MailAuthAccount?): MailAuthAccount {
        val username = username?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: previous?.username
            ?: userId.take(8)
        val display = name?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(givenName, familyName).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
            ?: previous?.displayName
            ?: username
        return MailAuthAccount(
            userId = userId,
            username = username,
            email = email?.takeIf { it.isNotBlank() } ?: "$username@gothwadtech.com",
            displayName = display,
            avatarUrl = picture?.takeIf { it.isNotBlank() } ?: previous?.avatarUrl,
            addedAt = previous?.addedAt ?: now,
            lastSeenAt = now,
        )
    }

    companion object {
        /** Pending logins expire after ten minutes. */
        const val PENDING_LOGIN_TTL_MILLIS: Long = 10 * 60 * 1000L

        /** `lastSeenAt` is rewritten at most once per minute to avoid needless encrypted writes. */
        const val LAST_SEEN_TOUCH_INTERVAL_MILLIS: Long = 60_000L
    }
}
