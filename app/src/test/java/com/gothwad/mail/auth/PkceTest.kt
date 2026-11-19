package com.gothwad.mail.auth

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the S256 PKCE primitives against RFC 7636 and the Bansify Accounts contract. */
class PkceTest {

    @Test
    fun `challenge matches the RFC 7636 appendix B vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expected, Pkce.challenge(verifier))
    }

    @Test
    fun `challenge is base64url sha256 without padding`() {
        val verifier = Pkce.createVerifier()
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        assertEquals(expected, Pkce.challenge(verifier))
        assertTrue("Challenge must not contain padding", !Pkce.challenge(verifier).contains("="))
        assertTrue("Challenge must be URL safe", Pkce.challenge(verifier).none { it == '+' || it == '/' })
    }

    @Test
    fun `verifier is 64 random bytes rendered as 86 url safe characters`() {
        val verifier = Pkce.createVerifier()
        assertEquals(86, verifier.length)
        assertEquals(Pkce.VERIFIER_BYTES, 64)
        assertTrue("Verifier must stay inside the RFC 7636 43..128 bound", verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '~' })
    }

    @Test
    fun `state is 24 random bytes and unique per attempt`() {
        val first = Pkce.createState()
        val second = Pkce.createState()
        assertEquals(32, first.length)
        assertEquals(Pkce.STATE_BYTES, 24)
        assertNotEquals(first, second)
        assertNotEquals(Pkce.createVerifier(), Pkce.createVerifier())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank verifier is rejected`() {
        Pkce.challenge(" ")
    }

    @Test
    fun `authorize url carries every registered parameter and the S256 challenge`() {
        val pending = PendingLogin(
            state = "state-value",
            verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
            redirectUri = GothwadEndpoints.REDIRECT_URI_APP_LINK,
            createdAt = 1L,
        )
        val url = AuthorizeRequest.authorizeUrl(pending, loginHint = "pawan")
        assertTrue(url.startsWith("https://accounts.bansify.com/api/oauth/authorize?"))
        assertTrue(url.contains("client_id=gothwad-mail"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
        assertTrue(url.contains("state=state-value"))
        assertTrue(url.contains("login_hint=pawan"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fmail.bansify.com%2Foauth%2Fcallback"))
        assertTrue(url.contains("scope=email%20mail%20offline_access%20profile"))
    }

    @Test
    fun `choose account url wraps the authorize url`() {
        val pending = PendingLogin(state = "s", verifier = "v", createdAt = 1L)
        val authorize = AuthorizeRequest.authorizeUrl(pending)
        val chooser = AuthorizeRequest.chooseAccountUrl(authorize)
        assertTrue(
            "Chooser must sit on the site root, not under /api: $chooser",
            chooser.startsWith("https://accounts.bansify.com/signin?choose=1&next="),
        )
        assertTrue(chooser.contains("oauth%2Fauthorize"))
    }
}
