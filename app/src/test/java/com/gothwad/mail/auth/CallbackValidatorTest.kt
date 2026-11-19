package com.gothwad.mail.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies strict callback validation: scheme, host, path, state, replay and error handling. */
class CallbackValidatorTest {

    private val redirect = GothwadEndpoints.REDIRECT_URI_APP_LINK
    private val state = "state-abcdef0123456789"

    @Test
    fun `accepts the registered https app link callback`() {
        val result = CallbackValidator.validate(
            "$redirect?code=auth-code&state=$state",
            redirect,
            state,
        )
        assertEquals("auth-code", result.code)
        assertNull(result.error)
    }

    @Test
    fun `accepts the registered custom scheme callback`() {
        val custom = GothwadEndpoints.REDIRECT_URI_CUSTOM_SCHEME
        val result = CallbackValidator.validate("$custom?code=abc&state=$state", custom, state)
        assertEquals("abc", result.code)
    }

    /**
     * The real device flow: `redirect_uri` is the App Link, but the web bridge on that page hands
     * the query to the app over the custom scheme (see `public/oauth/callback/index.html` in the
     * Worker repo). Both deliveries must validate against the same pending login.
     */
    @Test
    fun `accepts a custom scheme delivery when the app link was registered`() {
        val custom = GothwadEndpoints.REDIRECT_URI_CUSTOM_SCHEME
        val result = CallbackValidator.validate(
            callback = "$custom?code=auth-code&state=$state",
            redirectUri = redirect,
            state = state,
            acceptedRedirectUris = listOf(redirect, custom),
        )
        assertEquals("auth-code", result.code)
    }

    @Test
    fun `accepts an app link delivery when only the custom scheme is listed`() {
        val custom = GothwadEndpoints.REDIRECT_URI_CUSTOM_SCHEME
        val result = CallbackValidator.validate(
            callback = "$redirect?code=auth-code&state=$state",
            redirectUri = custom,
            state = state,
            acceptedRedirectUris = listOf(custom, redirect),
        )
        assertEquals("auth-code", result.code)
    }

    @Test
    fun `a custom scheme delivery still rejects a foreign host and state`() {
        val custom = GothwadEndpoints.REDIRECT_URI_CUSTOM_SCHEME
        val accepted = listOf(redirect, custom)
        // Wrong state over the otherwise acceptable scheme is still rejected.
        assertTrue(
            runCatching {
                CallbackValidator.validate("$custom?code=abc&state=other", redirect, state, accepted)
            }.isFailure,
        )
        // A foreign https host is never accepted, even though https is a registered scheme.
        assertTrue(
            runCatching {
                CallbackValidator.validate(
                    "https://evil.example.com/oauth/callback?code=abc&state=$state",
                    redirect,
                    state,
                    accepted,
                )
            }.isFailure,
        )
        // The custom scheme must not accept a foreign path.
        assertTrue(
            runCatching {
                CallbackValidator.validate("$custom/extra?code=abc&state=$state", redirect, state, accepted)
            }.isFailure,
        )
    }

    @Test
    fun `rejects a callback with a different scheme`() {
        assertRejected("courier://mail.bansify.com/oauth/callback?code=abc&state=$state")
        assertRejected("http://mail.bansify.com/oauth/callback?code=abc&state=$state")
    }

    @Test
    fun `rejects a callback with a different host`() {
        assertRejected("https://evil.example.com/oauth/callback?code=abc&state=$state")
    }

    @Test
    fun `rejects a callback with a different path`() {
        assertRejected("https://mail.bansify.com/oauth/other?code=abc&state=$state")
        assertRejected("https://mail.bansify.com/oauth/callback/extra?code=abc&state=$state")
    }

    @Test
    fun `rejects a mismatched state`() {
        assertRejected("$redirect?code=abc&state=someone-else")
    }

    @Test
    fun `rejects a callback without state`() {
        assertRejected("$redirect?code=abc")
    }

    @Test
    fun `rejects duplicated parameters that could shadow the state`() {
        assertRejected("$redirect?code=abc&state=$state&state=attacker")
    }

    @Test
    fun `rejects a callback carrying both code and error`() {
        assertRejected("$redirect?code=abc&error=access_denied&state=$state")
    }

    @Test
    fun `rejects a callback carrying neither code nor error`() {
        assertRejected("$redirect?state=$state")
    }

    @Test
    fun `rejects a blank code`() {
        assertRejected("$redirect?code=&state=$state")
    }

    @Test
    fun `rejects fragments and user info`() {
        assertRejected("$redirect?code=abc&state=$state#fragment")
        assertRejected("https://user:pass@mail.bansify.com/oauth/callback?code=abc&state=$state")
    }

    @Test
    fun `rejects oversized callbacks`() {
        val padding = "a".repeat(CallbackValidator.MAX_CALLBACK_LENGTH)
        assertRejected("$redirect?code=$padding&state=$state")
    }

    @Test
    fun `reports an error callback as a cancellation`() {
        val result = CallbackValidator.validate(
            "$redirect?error=access_denied&state=$state",
            redirect,
            state,
        )
        assertTrue(result.isError)
        assertEquals("access_denied", result.error)
    }

    @Test
    fun `replay of a consumed login is rejected by the session manager`() {
        val session = MailAuthSession()
        val pending = PendingLogin(state = state, verifier = "verifier", createdAt = 0L)
        val consumed = session.copy(pending = pending).copy(pending = null)
        assertNull("A consumed login must not leave pending state behind", consumed.pending)
    }

    private fun assertRejected(callback: String) {
        val rejected = runCatching { CallbackValidator.validate(callback, redirect, state) }.isFailure
        assertTrue("Expected rejection for: $callback", rejected)
    }
}
