package com.bansify.mail.auth

/** Session state surfaced to the Mail UI, mapped from the shared Accounts SDK. */
sealed interface AuthSessionState {
    data object Initializing : AuthSessionState
    data object SignedOut : AuthSessionState
    data class SignedIn(val accounts: List<MailAuthAccount>, val activeAccount: MailAuthAccount?) : AuthSessionState
    data class StorageCorrupted(val reason: String) : AuthSessionState
}

/** One-shot auth notifications consumed by the ViewModel. */
sealed interface AuthEvent {
    data class SignedIn(val account: MailAuthAccount) : AuthEvent
    data class SignedOut(val account: MailAuthAccount?, val outcome: DisconnectOutcome) : AuthEvent
    data object LoginCancelled : AuthEvent
    data object BrowserUnavailable : AuthEvent

    /** A zero-browser peer sign-in finished; the account is already persisted. */
    data class SilentSignInSucceeded(val account: MailAuthAccount, val sourcePackage: String) : AuthEvent

    /** The silent attempt did not succeed, so the UI should offer an interactive sign-in. */
    data class SilentSignInFailed(val sourcePackage: String?, val reason: String) : AuthEvent
    data class Failed(val failure: MailAuthFailure) : AuthEvent
}

/** Raw token payload returned by `POST /oauth/token`. Kept for the native-auth models. */
data class TokenResponse(
    val accessToken: String = "",
    val refreshToken: String? = null,
    val expiresIn: Long = 0L,
    val scope: String? = null,
    val tokenType: String = "Bearer",
)
