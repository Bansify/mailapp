package com.gothwad.mail.auth

import java.security.MessageDigest

/**
 * Bounds how often one OAuth redirect is processed.
 *
 * A redirect can be delivered twice on a real device: Chrome may hand the App Link to the app and
 * the custom-scheme trampoline can also fire, and tapping the browser's "return to app" affordance
 * replays the intent. The second delivery would otherwise fail with `invalid_grant` (the code is
 * already exchanged) and surface an error to the user right after a successful sign-in.
 *
 * The comparison is on a SHA-256 of the callback URL, never on the code itself, so the code is not
 * retained in memory longer than the exchange needs it.
 */
class CallbackBudget(private val maxAttempts: Int = 1, private val capacity: Int = 8) {

    private val seen = object : LinkedHashMap<String, Int>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
            size > capacity
    }

    /** Returns false when this callback was already processed [maxAttempts] times. */
    @Synchronized
    fun tryAcquire(callbackUrl: String): Boolean {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(callbackUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val attempts = (seen[key] ?: 0) + 1
        seen[key] = attempts
        return attempts <= maxAttempts
    }
}
