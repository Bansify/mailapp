package com.gothwad.mail.auth

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Strict validation of the OAuth redirect callback.
 *
 * Every check mirrors the Bansify Accounts core validator so the Mail app cannot be tricked
 * into completing a login from a foreign scheme, host or path, from a replayed state value,
 * or from a callback that carries both `code` and `error`.
 *
 * ## Why more than one redirect URI is accepted
 *
 * The app registers two redirect URIs for the same logical callback:
 *
 * - `https://mail.bansify.com/oauth/callback` — the App Link, sent as `redirect_uri`;
 * - `com.gothwad.mail:/oauth/callback` — the custom scheme.
 *
 * Accounts sends the browser to the App Link, so that URL is what the token exchange must present
 * as `redirect_uri`. But the web bridge at `https://mail.bansify.com/oauth/callback` hands the
 * query over with an `intent://` / custom-scheme hop, and the browser may surface either URL to the
 * activity. Validating against a single URI made the custom-scheme delivery fail with
 * "Callback scheme does not match" — i.e. the user finished sign-in in Chrome, returned to the app,
 * and landed back on the welcome screen.
 *
 * What stays strict: a closed set of accepted URIs, an exact scheme + path match, an exact authority
 * match whenever the URI carries one, and the constant-time `state` check.
 */
object CallbackValidator {

    /** Upper bound on the accepted callback URL length, to bound parsing work. */
    const val MAX_CALLBACK_LENGTH: Int = 8192

    /** Thrown for any callback that fails validation. Never carries secret material. */
    class InvalidCallbackException(message: String) : IllegalArgumentException(message)

    /** Result of a successfully validated callback. Exactly one of [code] / [error] is set. */
    data class Result(val code: String?, val error: String?) {
        val isError: Boolean get() = error != null
    }

    /**
     * Validates [callback] against the registered [redirectUri] and the expected [state].
     *
     * @param acceptedRedirectUris every redirect URI this client may legitimately receive. Defaults
     *   to [redirectUri] alone, so callers that know only one URI keep the strictest behaviour.
     */
    fun validate(
        callback: String,
        redirectUri: String,
        state: String,
        acceptedRedirectUris: List<String> = listOf(redirectUri),
    ): Result {
        if (callback.isBlank()) throw InvalidCallbackException("Callback URL is empty")
        if (callback.length > MAX_CALLBACK_LENGTH) {
            throw InvalidCallbackException("Callback URL exceeds the allowed length")
        }
        if (state.isBlank()) throw InvalidCallbackException("Expected state is empty")

        val uri = runCatching { URI(callback) }
            .getOrElse { throw InvalidCallbackException("Callback URL is not a valid URI") }

        if (uri.isOpaque) throw InvalidCallbackException("Callback URL must be hierarchical")
        if (uri.rawFragment != null) throw InvalidCallbackException("Callback URL must not have a fragment")
        if (uri.rawUserInfo != null) throw InvalidCallbackException("Callback URL must not carry user info")

        val candidates = (acceptedRedirectUris + redirectUri).distinct()
            .mapNotNull { runCatching { URI(it) }.getOrNull() }
        if (candidates.isEmpty()) {
            throw InvalidCallbackException("Registered redirect URI is not valid")
        }

        val schemeMatches = candidates.any { it.scheme?.equals(uri.scheme, ignoreCase = true) == true }
        if (!schemeMatches) throw InvalidCallbackException("Callback scheme does not match")

        val sameScheme = candidates.filter { it.scheme?.equals(uri.scheme, ignoreCase = true) == true }
        // The authority is only compared when the registered form has one: a reverse-DNS custom
        // scheme (`com.gothwad.mail:/oauth/callback`) legitimately carries no host.
        val authorityMatches = sameScheme.any { expected ->
            expected.rawAuthority == null || expected.rawAuthority == uri.rawAuthority
        }
        if (!authorityMatches) throw InvalidCallbackException("Callback authority does not match")

        val pathMatches = sameScheme.any { it.rawPath == uri.rawPath }
        if (!pathMatches) throw InvalidCallbackException("Callback path does not match")

        val params = parseQuery(uri.rawQuery)
        val receivedState = params["state"] ?: throw InvalidCallbackException("Callback is missing state")
        if (!constantTimeEquals(state, receivedState)) {
            throw InvalidCallbackException("Callback state does not match")
        }

        val code = params["code"]
        val error = params["error"]
        if ((code == null) == (error == null)) {
            throw InvalidCallbackException("Callback must carry exactly one of code or error")
        }
        val value = code ?: error
        if (value.isNullOrBlank()) throw InvalidCallbackException("Callback code/error is blank")
        return Result(code = code, error = error)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val entries = rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .map { pair ->
                val parts = pair.split('=', limit = 2)
                decode(parts[0]) to decode(parts.getOrElse(1) { "" })
            }
        // A duplicated parameter would let an attacker shadow the state/code; reject outright.
        if (entries.map { it.first }.distinct().size != entries.size) {
            throw InvalidCallbackException("Callback carries duplicated parameters")
        }
        return entries.toMap()
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }
            .getOrElse { throw InvalidCallbackException("Callback contains malformed encoding") }

    /** Constant-time comparison so state validation cannot be timed. */
    fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
