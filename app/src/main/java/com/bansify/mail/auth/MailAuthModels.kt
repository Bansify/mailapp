package com.bansify.mail.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A Bansify Account identity, exactly as `/oauth/userinfo` returns it. */
@Serializable
data class GothwadUserInfo(
    @SerialName("sub") val userId: String,
    @SerialName("preferred_username") val username: String? = null,
    val name: String? = null,
    @SerialName("given_name") val givenName: String? = null,
    @SerialName("family_name") val familyName: String? = null,
    val email: String? = null,
    val picture: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    val scopes: List<String> = emptyList(),
)

/** One signed-in account as persisted in the encrypted Mail token store. */
@Serializable
data class MailAuthAccount(
    val userId: String,
    val username: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val addedAt: Long = 0L,
    val lastSeenAt: Long = 0L,
) {
    /** The canonical mailbox address shown in the UI. */
    val mailboxAddress: String get() = email.ifBlank { "$username@bansify.com" }

    /** One or two letter monogram used by the existing Material 3 avatars. */
    val monogram: String
        get() = displayName.trim().takeIf { it.isNotEmpty() }
            ?.split(" ")
            ?.filter { it.isNotBlank() }
            ?.take(2)
            ?.joinToString("") { it.first().uppercase() }
            ?: username.take(1).uppercase()
}

/** Stored OAuth credential for one account. Refresh tokens are rotated atomically. */
@Serializable
data class MailCredential(
    val account: MailAuthAccount,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L,
    val scopes: List<String> = emptyList(),
) {
    fun isExpiring(now: Long, skewMillis: Long = TOKEN_REFRESH_SKEW_MILLIS): Boolean =
        expiresAt - now <= skewMillis

    companion object {
        /** Refresh when the access token is within one minute of expiry. */
        const val TOKEN_REFRESH_SKEW_MILLIS: Long = 60_000L
    }
}

/** A pending login attempt, persisted so process death cannot lose the PKCE verifier. */
@Serializable
data class PendingLogin(
    val state: String,
    val verifier: String,
    val redirectUri: String = GothwadEndpoints.REDIRECT_URI,
    val createdAt: Long = 0L,
    val loginHint: String? = null,
)

/** Everything persisted in the encrypted store. */
@Serializable
data class MailAuthSession(
    val credentials: List<MailCredential> = emptyList(),
    val activeUserId: String? = null,
    val pending: PendingLogin? = null,
    val deviceRemovalTombstones: Map<String, Long> = emptyMap(),
) {
    fun credentialFor(userId: String): MailCredential? = credentials.find { it.account.userId == userId }

    fun activeCredential(): MailCredential? =
        activeUserId?.let { id -> credentialFor(id) } ?: credentials.firstOrNull()

    fun accounts(): List<MailAuthAccount> = credentials.map { it.account }
}

/** Truthful result of a sign-out / disconnect action. */
enum class DisconnectOutcome {
    /** `POST /oauth/revoke` succeeded and the local credential was cleared. */
    RevokedOnServer,

    /** The server reported the grant already invalid; the local credential was cleared. */
    AlreadyInvalidOnServer,

    /** Local credential cleared, but revocation could not be confirmed (offline/transient). */
    OfflineRemovedLocally,

    /** Local-only sign-out: the server grant was intentionally left untouched. */
    LocalOnly,
}

/** Failure modes surfaced to the signed-out and settings screens. */
enum class MailAuthFailure {
    BrowserUnavailable,
    CallbackExpired,
    InvalidCallback,
    LoginCancelled,
    SessionExpired,
    StorageUnavailable,
    Unavailable,
}

/** Signals an OAuth failure; [rejected] means the server invalidated the credential. */
class MailOAuthException(
    val rejected: Boolean,
    message: String = if (rejected) "Credential rejected by Bansify Accounts" else "OAuth request failed",
    cause: Throwable? = null,
) : java.io.IOException(message, cause)

/** Signals a token exchange/refresh rejected by the server (`invalid_grant` / `invalid_token`). */
class InvalidCredentialException(message: String = "The Bansify Account credential is no longer valid") :
    java.io.IOException(message)

/** Thrown when the encrypted store cannot be unlocked or write. */
class AuthStorageException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

/**
 * Thrown when a callback arrives but no usable pending login exists: the ten-minute window
 * expired, the state was already consumed, or another sign-in superseded it.
 */
class PendingLoginUnavailableException(
    message: String = "The sign-in attempt is no longer valid; please sign in again",
) : java.io.IOException(message)

/** Thrown when the login was cancelled in the browser (`error=access_denied`). */
class LoginCancelledException : java.io.IOException("Sign-in was cancelled")
