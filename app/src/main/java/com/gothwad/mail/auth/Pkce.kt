package com.gothwad.mail.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * OAuth 2.0 PKCE (`S256`) helpers for the Gothwad Accounts native flow.
 *
 * The verifier is a cryptographically random 64-byte value rendered as unpadded
 * Base64url (86 characters, inside the RFC 7636 43..128 bound), and the challenge is
 * `BASE64URL-ENCODE(SHA256(ASCII(verifier)))`.
 */
object Pkce {

    /** RFC 7636 verifier length in bytes: 64 random bytes -> 86 characters. */
    const val VERIFIER_BYTES: Int = 64

    /** CSRF `state` length in bytes: 24 random bytes -> 32 characters. */
    const val STATE_BYTES: Int = 24

    private val secureRandom = SecureRandom()

    /** Creates a fresh, cryptographically random code verifier. */
    fun createVerifier(): String = randomToken(VERIFIER_BYTES)

    /** Creates a fresh, cryptographically random `state` value. */
    fun createState(): String = randomToken(STATE_BYTES)

    /** Returns the `S256` code challenge for [verifier]. */
    fun challenge(verifier: String): String {
        require(verifier.isNotBlank()) { "Code verifier must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return encodeUrlSafe(digest)
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        secureRandom.nextBytes(buffer)
        return encodeUrlSafe(buffer)
    }

    private fun encodeUrlSafe(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}
