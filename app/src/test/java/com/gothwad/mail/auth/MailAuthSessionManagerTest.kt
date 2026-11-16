package com.gothwad.mail.auth

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake OAuth service with call counting, so single-flight and eviction behaviour is observable. */
private class FakeOAuthService(
    var expiresIn: Long = 3600L,
    var refreshResponse: () -> TokenResponse = { TokenResponse("access-2", "refresh-2", 3600L) },
    var exchangeResponse: () -> TokenResponse = { TokenResponse("access-1", "refresh-1", 3600L) },
    var userInfo: (String) -> GothwadUserInfo = { token ->
        GothwadUserInfo(
            userId = "user-1",
            username = "pawan",
            name = "Pawan Gothwad",
            email = "pawan@gothwadtech.com",
            picture = "https://accounts.gothwadtech.com/avatar/$token.png",
        )
    },
    var revokeStatus: RevokeStatus = RevokeStatus.Revoked,
) : MailOAuthService {
    val refreshCalls = AtomicInteger()
    val exchangeCalls = AtomicInteger()
    val revokeCalls = AtomicInteger()
    var lastRevokedToken: String? = null

    override fun exchangeAuthorizationCode(code: String, codeVerifier: String, redirectUri: String): TokenResponse {
        exchangeCalls.incrementAndGet()
        return exchangeResponse()
    }

    override fun refreshToken(refreshToken: String): TokenResponse {
        refreshCalls.incrementAndGet()
        return refreshResponse()
    }

    /** Named so it does not shadow the property accessor above. */
    private val userInfoResolver: (String) -> GothwadUserInfo
        get() = userInfo

    override fun userInfo(accessToken: String): GothwadUserInfo = userInfoResolver.invoke(accessToken)

    override fun revoke(refreshToken: String?, accessToken: String?): RevokeStatus {
        revokeCalls.incrementAndGet()
        lastRevokedToken = refreshToken
        return revokeStatus
    }
}

class MailAuthSessionManagerTest {

    private val redirect = GothwadEndpoints.REDIRECT_URI_APP_LINK
    private val now = 1_700_000_000_000L

    private fun signedInSession(
        accessToken: String = "access-old",
        refreshToken: String? = "refresh-old",
        expiresAt: Long = 1_700_000_000_000L + 3_600_000L,
        addedAt: Long = 1_700_000_000_000L,
    ): MailAuthSession {
        val account = MailAuthAccount(
            userId = "user-1",
            username = "pawan",
            email = "pawan@gothwadtech.com",
            displayName = "Pawan Gothwad",
            addedAt = addedAt,
            lastSeenAt = addedAt,
        )
        return MailAuthSession(
            credentials = listOf(
                MailCredential(
                    account = account,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = expiresAt,
                ),
            ),
            activeUserId = "user-1",
        )
    }

    private fun manager(
        store: MailTokenStore,
        oauth: MailOAuthService,
        clock: () -> Long = { now },
    ) = MailAuthSessionManager(store = store, oauth = oauth, clock = clock)

    @Test
    fun `load publishes the signed-in state from the encrypted store`() = runTest {
        val store = InMemoryTokenStore(signedInSession())
        val session = manager(store, FakeOAuthService())
        session.load()

        val state = session.state.value
        assertTrue(state is AuthSessionState.SignedIn)
        assertEquals("pawan", (state as AuthSessionState.SignedIn).activeAccount?.username)
    }

    @Test
    fun `beginLogin persists a pending login with a fresh state and verifier`() = runTest {
        val store = InMemoryTokenStore()
        val session = manager(store, FakeOAuthService())
        session.load()

        val pending = session.beginLogin(loginHint = "  pawan  ")
        assertEquals(86, pending.verifier.length)
        assertEquals(32, pending.state.length)
        assertEquals("pawan", pending.loginHint)
        assertEquals(redirect, pending.redirectUri)
        assertEquals(pending.state, store.value.pending?.state)
        assertEquals(pending.verifier, store.value.pending?.verifier)
    }

    @Test
    fun `completeLogin consumes the pending login so it cannot be replayed`() = runTest {
        val store = InMemoryTokenStore()
        val oauth = FakeOAuthService()
        val session = manager(store, oauth)
        session.load()
        val pending = session.beginLogin()
        val callback = "$redirect?code=code-1&state=${pending.state}"

        val account = session.completeLogin(callback)
        assertEquals("pawan", account.username)
        assertEquals(1, oauth.exchangeCalls.get())
        assertNull(store.value.pending)
        assertNotNull(store.value.credentialFor("user-1"))

        val replay = runCatching { session.completeLogin(callback) }
        assertTrue("A replayed callback must fail", replay.isFailure)
        assertEquals("The code must be exchanged only once", 1, oauth.exchangeCalls.get())
    }

    @Test
    fun `completeLogin rejects an expired pending login`() = runTest {
        val store = InMemoryTokenStore()
        var clock = now
        val oauth = FakeOAuthService()
        val session = manager(store, oauth) { clock }
        session.load()
        val pending = session.beginLogin()

        clock += MailAuthSessionManager.PENDING_LOGIN_TTL_MILLIS + 1
        val result = runCatching { session.completeLogin("$redirect?code=abc&state=${pending.state}") }

        assertTrue(result.isFailure)
        assertEquals(0, oauth.exchangeCalls.get())
    }

    @Test
    fun `completeLogin rejects the wrong state without exchanging the code`() = runTest {
        val store = InMemoryTokenStore()
        val oauth = FakeOAuthService()
        val session = manager(store, oauth)
        session.load()
        session.beginLogin()

        val result = runCatching { session.completeLogin("$redirect?code=abc&state=wrong") }
        assertTrue(result.isFailure)
        assertEquals(0, oauth.exchangeCalls.get())
    }

    @Test
    fun `token refresh is single flight across concurrent callers`() = runTest {
        val store = InMemoryTokenStore(signedInSession(expiresAt = now + 1_000L))
        val oauth = FakeOAuthService()
        val session = manager(store, oauth)
        session.load()

        val tokens = (1..8).map { async { session.accessToken() } }.awaitAll()

        assertEquals("Exactly one refresh must reach Gothwad Accounts", 1, oauth.refreshCalls.get())
        assertTrue(tokens.all { it == "access-2" })
    }

    @Test
    fun `expired token is refreshed and the rotated refresh token is persisted`() = runTest {
        val store = InMemoryTokenStore(
            signedInSession(
                accessToken = "access-old",
                refreshToken = "refresh-old",
                expiresAt = now + 30_000L,
            ),
        )
        val oauth = FakeOAuthService(refreshResponse = { TokenResponse("access-new", "refresh-new", 3600L) })
        val session = manager(store, oauth)
        session.load()

        val token = session.accessToken()

        assertEquals("access-new", token)
        val stored = store.value.credentialFor("user-1")
        assertEquals("access-new", stored?.accessToken)
        assertEquals("refresh-new", stored?.refreshToken)
        assertTrue("The new expiry must be in the future", (stored?.expiresAt ?: 0L) > now)
    }

    @Test
    fun `valid token within sixty second skew triggers a refresh`() = runTest {
        val store = InMemoryTokenStore(signedInSession(expiresAt = now + 60_000L))
        val oauth = FakeOAuthService()
        val session = manager(store, oauth)
        session.load()

        session.accessToken()
        assertEquals(1, oauth.refreshCalls.get())
    }

    @Test
    fun `valid token outside sixty second skew is reused without a network call`() = runTest {
        val store = InMemoryTokenStore(signedInSession(expiresAt = now + 61_000L))
        val oauth = FakeOAuthService()
        val session = manager(store, oauth)
        session.load()

        assertEquals("access-old", session.accessToken())
        assertEquals(0, oauth.refreshCalls.get())
    }

    @Test
    fun `invalid_grant during refresh evicts the credential and surfaces session expiry`() = runTest {
        val store = InMemoryTokenStore(signedInSession(expiresAt = now + 1_000L))
        val oauth = FakeOAuthService(refreshResponse = { throw MailOAuthException(rejected = true) })
        val session = manager(store, oauth)
        session.load()

        val result = runCatching { session.accessToken() }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidCredentialException)
        assertNull("The rejected credential must be evicted", store.value.credentialFor("user-1"))
        assertTrue(store.value.credentials.isEmpty())
        assertEquals(AuthSessionState.SignedOut, session.state.value)
        assertEquals(MailAuthFailure.SessionExpired, session.failure.value)
    }

    @Test
    fun `transient refresh failure keeps the credential for a retry`() = runTest {
        val store = InMemoryTokenStore(signedInSession(expiresAt = now + 1_000L))
        val oauth = FakeOAuthService(refreshResponse = { throw MailOAuthException(rejected = false) })
        val session = manager(store, oauth)
        session.load()

        val result = runCatching { session.accessToken() }

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is InvalidCredentialException)
        assertNotNull("A network failure must not evict the credential", store.value.credentialFor("user-1"))
    }

    @Test
    fun `signing out of mail keeps the server grant untouched`() = runTest {
        val store = InMemoryTokenStore(signedInSession())
        val oauth = FakeOAuthService()
        val session = manager(store, oauth)
        session.load()

        val outcome = session.signOut("user-1", revokeOnServer = false)

        assertEquals(DisconnectOutcome.LocalOnly, outcome)
        assertEquals("Revoke must not be called for a local sign-out", 0, oauth.revokeCalls.get())
        assertTrue(store.value.credentials.isEmpty())
        assertEquals(AuthSessionState.SignedOut, session.state.value)
    }

    @Test
    fun `disconnecting from the server calls oauth revoke before dropping local tokens`() = runTest {
        val store = InMemoryTokenStore(signedInSession())
        val oauth = FakeOAuthService(revokeStatus = RevokeStatus.Revoked)
        val session = manager(store, oauth)
        session.load()

        val outcome = session.signOut("user-1", revokeOnServer = true)

        assertEquals(DisconnectOutcome.RevokedOnServer, outcome)
        assertEquals(1, oauth.revokeCalls.get())
        assertEquals("refresh-old", oauth.lastRevokedToken)
        assertTrue(store.value.credentials.isEmpty())
        assertEquals(DisconnectOutcome.RevokedOnServer, session.lastDisconnectOutcome.value)
    }

    @Test
    fun `disconnect reports an offline outcome when revocation cannot be confirmed`() = runTest {
        val store = InMemoryTokenStore(signedInSession())
        val oauth = FakeOAuthService(revokeStatus = RevokeStatus.Failed)
        val session = manager(store, oauth)
        session.load()

        val outcome = session.signOut("user-1", revokeOnServer = true)

        assertEquals(DisconnectOutcome.OfflineRemovedLocally, outcome)
        assertTrue(store.value.credentials.isEmpty())
    }

    @Test
    fun `disconnect reports an already invalid grant`() = runTest {
        val store = InMemoryTokenStore(signedInSession())
        val oauth = FakeOAuthService(revokeStatus = RevokeStatus.InvalidGrant)
        val session = manager(store, oauth)
        session.load()

        assertEquals(DisconnectOutcome.AlreadyInvalidOnServer, session.signOut("user-1", revokeOnServer = true))
    }

    @Test
    fun `removing an account from the device records a tombstone for peers`() = runTest {
        val store = InMemoryTokenStore(signedInSession(addedAt = now - 10_000L))
        val session = manager(store, FakeOAuthService())
        session.load()

        val removedAt = session.removeFromDevice("user-1", removedAt = now)

        assertEquals(now, removedAt)
        assertEquals(now, store.value.deviceRemovalTombstones["user-1"])
        assertTrue(store.value.credentials.isEmpty())
        assertEquals(0, FakeOAuthService().revokeCalls.get())
    }

    @Test
    fun `a peer tombstone removes a stale credential but keeps a newer sign-in`() = runTest {
        val store = InMemoryTokenStore(
            signedInSession(addedAt = now - 10_000L).let { session ->
                session.copy(
                    credentials = session.credentials.map { credential ->
                        credential.copy(account = credential.account.copy(lastSeenAt = now - 10_000L))
                    },
                )
            },
        )
        val session = manager(store, FakeOAuthService())
        session.load()

        session.applyPeerDeviceRemoval("user-1", now)
        assertTrue("A stale credential must be removed", store.value.credentials.isEmpty())
        assertEquals(now, store.value.deviceRemovalTombstones["user-1"])

        // Signing in again after the removal supersedes the tombstone.
        val fresh = signedInSession(addedAt = now + 5_000L).let { sessionState ->
            sessionState.copy(
                credentials = sessionState.credentials.map { credential ->
                    credential.copy(account = credential.account.copy(lastSeenAt = now + 5_000L))
                },
            )
        }
        val secondStore = InMemoryTokenStore(fresh)
        val second = manager(secondStore, FakeOAuthService())
        second.load()
        second.applyPeerDeviceRemoval("user-1", now)
        assertNotNull("A newer sign-in must survive an older tombstone", secondStore.value.credentialFor("user-1"))
    }

    @Test
    fun `switching accounts updates the active user and last seen time`() = runTest {
        val store = InMemoryTokenStore()
        val session = manager(store, FakeOAuthService())
        session.load()
        val first = session.completeLogin(
            "$redirect?code=1&state=${session.beginLogin().state}",
        ).let { it }
        val secondPending = session.beginLogin()
        val second = session.completeLogin("$redirect?code=2&state=${secondPending.state}")

        assertEquals(second.userId, session.activeAccountId())

        session.switchTo(first.userId)
        assertEquals(first.userId, session.activeAccountId())
        assertEquals(2, session.accounts().size)
    }

    @Test
    fun `directory snapshot never contains tokens`() = runTest {
        val store = InMemoryTokenStore(signedInSession())
        val session = manager(store, FakeOAuthService())
        session.load()

        val snapshot = session.directorySnapshot()
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            com.gothwad.mail.auth.broker.DirectorySnapshot.serializer(),
            snapshot,
        )

        assertEquals(1, snapshot.accounts.size)
        assertEquals("pawan", snapshot.accounts.first().username)
        assertFalse("Access tokens must never be shared", encoded.contains("access-old"))
        assertFalse("Refresh tokens must never be shared", encoded.contains("refresh-old"))
    }

    @Test
    fun `a corrupted store surfaces storage corruption without crashing`() = runTest {
        val brokenStore = object : MailTokenStore {
            override fun read(): MailAuthSession = throw AuthStorageException("unreadable")
            override fun write(value: MailAuthSession) = Unit
        }
        val session = manager(brokenStore, FakeOAuthService())
        session.load()

        assertTrue(session.state.value is AuthSessionState.StorageCorrupted)
        assertEquals(MailAuthFailure.StorageUnavailable, session.failure.value)

        session.recoverCorruptedStorage()
        assertEquals(AuthSessionState.SignedOut, session.state.value)
        assertNull(session.failure.value)
    }
}
