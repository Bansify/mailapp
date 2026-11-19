package com.gothwad.mail.auth

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the direct native `/api/mobile/auth/{endpoint}` client and the session manager paths that adopt
 * the tokens it returns, so sign-in works without any browser.
 */
class NativeAuthTest {

    private lateinit var server: MockWebServer
    private lateinit var client: NativeAuthClient
    private val recorded = mutableListOf<RecordedRequest>()
    private var mfaRequired = false
    private var signUpStatus = 201

    private val config = AuthConfig(
        packageName = "com.gothwad.mail",
        clientId = "gothwad-mail",
        redirectUri = GothwadEndpoints.REDIRECT_URI_CUSTOM_SCHEME,
        scopes = GothwadEndpoints.SCOPES,
        accountsBaseUrl = "https://accounts.bansify.com/api",
        mailApiBaseUrl = GothwadEndpoints.MAIL_API_BASE_URL,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded.add(request)
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/mobile/auth/check-identifier") -> json(
                        """{"ok":true,"exists":true,"kind":"username"}""",
                    )
                    path.startsWith("/api/mobile/auth/check-username") -> json(
                        """{"ok":true,"available":true,"valid":true,"email":"newuser@bansify.com"}""",
                    )
                    path.startsWith("/api/mobile/auth/signin/mfa") -> json(tokensJson())
                    path.startsWith("/api/mobile/auth/signin") -> if (mfaRequired) {
                        MockResponse().setResponseCode(202).setBody(
                            """{"ok":true,"mfa_required":true,"challenge":"${"a".repeat(64)}",
                               "expires_in":300,"message":"Enter your authenticator code"}""",
                        )
                    } else {
                        json(tokensJson())
                    }
                    path.startsWith("/api/mobile/auth/signup") ->
                        MockResponse().setResponseCode(signUpStatus).setBody(tokensJson())
                    path.startsWith("/api/mobile/auth/forgot-password") ->
                        json("""{"ok":true,"message":"Reset link sent"}""")
                    path.startsWith("/api/mobile/auth/reset-password") ->
                        json("""{"ok":true,"message":"Password updated"}""")
                    path.startsWith("/api/oauth/userinfo") -> json(
                        """{"sub":"user-native","preferred_username":"newuser",
                           "name":"New User","email":"newuser@bansify.com"}""",
                    )
                    path.startsWith("/api/oauth/token") -> json(tokensJson())
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}""")
                }
            }
        }
        server.start()
        client = NativeAuthClient(
            config = config.copy(accountsBaseUrl = server.url("/api").toString().trimEnd('/')),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sign-in posts the client id scope identifier and password`() = runTest {
        val result = client.signIn("newuser", "hunter2")

        val request = recorded.first { it.path.orEmpty().contains("/mobile/auth/signin") }
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"client_id\":\"gothwad-mail\""))
        assertTrue(body.contains("\"identifier\":\"newuser\""))
        assertTrue(body.contains("\"password\":\"hunter2\""))
        assertTrue(body.contains("\"scope\":\"email mail offline_access profile\""))
        assertTrue(result is NativeAuthResult.Tokens)
        val tokens = (result as NativeAuthResult.Tokens).tokens
        assertEquals("native-access", tokens.accessToken)
        assertEquals("native-refresh", tokens.refreshToken)
        assertEquals("newuser", result.user?.username)
    }

    @Test
    fun `a 202 with mfa_required returns the challenge instead of an error`() = runTest {
        mfaRequired = true

        val result = client.signIn("newuser", "hunter2")

        assertTrue(result is NativeAuthResult.MfaRequired)
        val mfa = result as NativeAuthResult.MfaRequired
        assertEquals(64, mfa.challenge.length)
        assertEquals(300, mfa.expiresIn)
    }

    @Test
    fun `mfa verification exchanges the challenge and the six digit code`() = runTest {
        val result = client.verifyMfa("challenge-hex", "123456")

        val request = recorded.first { it.path.orEmpty().contains("/signin/mfa") }
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"challenge\":\"challenge-hex\""))
        assertTrue(body.contains("\"code\":\"123456\""))
        assertTrue(result is NativeAuthResult.Tokens)
    }

    @Test
    fun `sign-up sends the profile fields and accepts 201`() = runTest {
        val result = client.signUp(
            ApiNativeSignUpRequest(
                clientId = "",
                scope = "",
                firstName = "New",
                lastName = "User",
                username = "newuser",
                password = "hunter2",
                recoveryEmail = "backup@example.com",
                phoneNumber = null,
                dateOfBirth = "1995-04-03",
                gender = "other",
            ),
        )

        val body = recorded.first { it.path.orEmpty().contains("/signup") }.body.readUtf8()
        assertTrue(body.contains("\"first_name\":\"New\""))
        assertTrue(body.contains("\"recovery_email\":\"backup@example.com\""))
        assertTrue(body.contains("\"date_of_birth\":\"1995-04-03\""))
        // The client fills in its own client id and scope.
        assertTrue(body.contains("\"client_id\":\"gothwad-mail\""))
        assertTrue(result is NativeAuthResult.Tokens)
    }

    @Test
    fun `identifier and username probes map their answers`() = runTest {
        val identifier = client.checkIdentifier("newuser")
        assertTrue(identifier.exists)
        assertEquals("username", identifier.kind)

        val username = client.checkUsername("newuser")
        assertTrue(username.available)
        assertEquals("newuser@bansify.com", username.email)
    }

    @Test
    fun `forgot and reset password report the server acknowledgement`() = runTest {
        assertTrue(client.forgotPassword("newuser").ok)
        assertTrue(recorded.last().body.readUtf8().contains("\"username\":\"newuser\""))

        assertTrue(client.resetPassword("token-1", "brand-new-password").ok)
        val body = recorded.last().body.readUtf8()
        assertTrue(body.contains("\"token\":\"token-1\""))
        assertTrue(body.contains("\"password\":\"brand-new-password\""))
    }

    @Test
    fun `a rejected credential surfaces the server error`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(401).setBody("""{"error":"invalid_credentials"}""")
        }

        val error = runCatching { client.signIn("newuser", "wrong") }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is MailOAuthException)
        assertTrue("401 must mark the credential as rejected", (error as MailOAuthException).rejected)
        assertEquals("invalid_credentials", error.message)
    }

    @Test
    fun `session manager stores the account from native tokens`() = runTest {
        val store = InMemoryTokenStore()
        val oauth = FakeOAuth()
        val session = MailAuthSessionManager(store, oauth, config)
        session.load()

        val account = session.completeNativeLogin(
            TokenResponse("native-access", "native-refresh", 900L, scope = "email mail offline_access profile"),
            GothwadUserInfo(
                userId = "user-native",
                username = "newuser",
                name = "New User",
                email = "newuser@bansify.com",
            ),
        )

        assertEquals("user-native", account.userId)
        assertEquals("user-native", session.activeAccountId())
        val credential = store.value.credentialFor("user-native")
        assertNotNull(credential)
        assertEquals("native-access", credential!!.accessToken)
        assertEquals(listOf("email", "mail", "offline_access", "profile"), credential.scopes)
    }

    @Test
    fun `copying several peer accounts keeps all of them and the first one active`() = runTest {
        val store = InMemoryTokenStore()
        val oauth = FakeOAuth()
        val session = MailAuthSessionManager(store, oauth, config)
        session.load()

        listOf("one", "two", "three").forEach { name ->
            session.completeNativeLogin(
                TokenResponse("$name-access", "$name-refresh", 900L),
                GothwadUserInfo(
                    userId = "user-$name",
                    username = name,
                    name = name,
                    email = "$name@bansify.com",
                ),
            )
        }

        assertEquals(
            "all three peer accounts must be stored",
            listOf("user-one", "user-two", "user-three"),
            store.value.credentials.map { it.account.userId },
        )
        assertEquals(
            "the first copied account stays active instead of the last one",
            "user-one",
            session.activeAccountId(),
        )
    }

    @Test
    fun `session manager redeems a peer issued code with its own verifier`() = runTest {
        val store = InMemoryTokenStore()
        val oauth = FakeOAuth()
        val session = MailAuthSessionManager(store, oauth, config)
        session.load()
        val verifier = Pkce.createVerifier()

        val account = session.completePeerLogin(
            code = "peer-code-1",
            codeVerifier = verifier,
            redirectUri = GothwadEndpoints.REDIRECT_URI_CUSTOM_SCHEME,
        )

        assertEquals("peer-code-1", oauth.lastCode)
        assertEquals(verifier, oauth.lastVerifier)
        assertEquals(GothwadEndpoints.REDIRECT_URI_CUSTOM_SCHEME, oauth.lastRedirect)
        assertEquals("peer-user", account.userId)
        assertEquals(1, store.value.credentials.size)
    }

    @Test
    fun `an unreadable sign-in body fails instead of storing a partial account`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
        }

        val error = runCatching { client.signIn("newuser", "hunter2") }.exceptionOrNull()

        assertTrue(
            "A response without tokens must not be treated as a success",
            error is MailOAuthException,
        )
        assertTrue((error as MailOAuthException).message!!.contains("access token"))
        assertFalse(error.rejected)
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun tokensJson(): String = """
        {"ok":true,"access_token":"native-access","refresh_token":"native-refresh",
         "expires_in":900,"token_type":"Bearer","scope":"email mail offline_access profile",
         "user":{"user_id":"user-native","preferred_username":"newuser","name":"New User",
                 "email":"newuser@bansify.com"}}
    """.trimIndent()

    /** Minimal OAuth fake; records the peer-code redemption arguments. */
    private class FakeOAuth : MailOAuthService {
        var lastCode: String? = null
        var lastVerifier: String? = null
        var lastRedirect: String? = null

        override fun exchangeAuthorizationCode(
            code: String,
            codeVerifier: String,
            redirectUri: String,
        ): TokenResponse {
            lastCode = code
            lastVerifier = codeVerifier
            lastRedirect = redirectUri
            return TokenResponse("peer-access", "peer-refresh", 900L, scope = "email mail offline_access profile")
        }

        override fun refreshToken(refreshToken: String): TokenResponse =
            TokenResponse("peer-access", "peer-refresh", 900L)

        override fun userInfo(accessToken: String): GothwadUserInfo = GothwadUserInfo(
            userId = "peer-user",
            username = "peeruser",
            name = "Peer User",
            email = "peeruser@bansify.com",
        )

        override fun revoke(refreshToken: String?, accessToken: String?): RevokeStatus = RevokeStatus.Revoked
    }
}
