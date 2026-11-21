package com.bansify.mail.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the Bansify Accounts native mobile auth API (`/api/mobile/auth/{endpoint}`).
 *
 * These endpoints authenticate straight from the app over OkHttp, so Bansify Email can sign a user
 * in even when the device has no browser installed. The browser (Custom Tabs) OAuth flow stays
 * available as a secondary option and both paths produce the same [TokenResponse] pair.
 */

/** `POST /api/mobile/auth/check-identifier`. */
@Serializable
data class ApiCheckIdentifierRequest(val identifier: String)

@Serializable
data class ApiCheckIdentifierResult(
    val ok: Boolean = false,
    val exists: Boolean = false,
    val kind: String? = null,
    val error: String? = null,
)

/** `POST /api/mobile/auth/check-username`. */
@Serializable
data class ApiCheckUsernameRequest(val username: String)

@Serializable
data class ApiCheckUsernameResult(
    val ok: Boolean = false,
    val available: Boolean = false,
    val valid: Boolean = false,
    val email: String? = null,
    val error: String? = null,
)

/** `POST /api/mobile/auth/signin`. */
@Serializable
data class ApiNativeSignInRequest(
    @SerialName("client_id") val clientId: String,
    val scope: String,
    val identifier: String,
    val password: String,
)

/** `POST /api/mobile/auth/signin/mfa`. */
@Serializable
data class ApiNativeMfaRequest(
    @SerialName("client_id") val clientId: String,
    val scope: String,
    val challenge: String,
    val code: String,
)

/** `POST /api/mobile/auth/signup`. */
@Serializable
data class ApiNativeSignUpRequest(
    @SerialName("client_id") val clientId: String,
    val scope: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val username: String,
    val password: String,
    @SerialName("recovery_email") val recoveryEmail: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("date_of_birth") val dateOfBirth: String? = null,
    val gender: String? = null,
)

/** `POST /api/mobile/auth/forgot-password`. */
@Serializable
data class ApiNativeForgotPasswordRequest(val username: String)

/** `POST /api/mobile/auth/reset-password`. */
@Serializable
data class ApiNativeResetPasswordRequest(val token: String, val password: String)

/** Generic acknowledgement for the password endpoints. */
@Serializable
data class ApiNativeOkResult(val ok: Boolean = false, val message: String? = null, val error: String? = null)

/**
 * Outcome of a native credential exchange.
 *
 * Either tokens are present, or the account needs a second factor ([challenge] is set). The
 * server answers `202` with `mfa_required` for the second case, so the UI can move to the
 * six-digit step without treating it as an error.
 */
sealed interface NativeAuthResult {
    data class Tokens(val tokens: TokenResponse, val user: NativeUser?) : NativeAuthResult

    data class MfaRequired(val challenge: String, val expiresIn: Int, val message: String?) : NativeAuthResult
}

/** Result of a native auth step as the UI sees it. */
sealed interface NativeAuthOutcome {
    /** Credentials are stored and the account is active. */
    data class Success(val account: MailAuthAccount) : NativeAuthOutcome

    /** The account has a second factor; ask for the six-digit code and call `verifyMfa`. */
    data class MfaRequired(val challenge: String, val expiresIn: Int, val message: String?) : NativeAuthOutcome

    /** The step failed; [message] is safe to show and [rejected] means the credential was refused. */
    data class Failed(val message: String, val rejected: Boolean) : NativeAuthOutcome
}

/** Identity block embedded in native sign-in / sign-up responses. */
@Serializable
data class NativeUser(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("sub") val subject: String? = null,
    @SerialName("preferred_username") val username: String? = null,
    val name: String? = null,
    @SerialName("given_name") val givenName: String? = null,
    @SerialName("family_name") val familyName: String? = null,
    val email: String? = null,
    val picture: String? = null,
) {
    /** Resolves whichever identifier the server used for the immutable subject. */
    fun toUserInfo(): GothwadUserInfo? {
        val resolved = (userId ?: subject ?: id)?.takeIf { it.isNotBlank() } ?: return null
        return GothwadUserInfo(
            userId = resolved,
            username = username,
            name = name,
            givenName = givenName,
            familyName = familyName,
            email = email,
            picture = picture,
        )
    }
}

/**
 * Wire form of a native auth response body.
 *
 * The endpoints return either a token pair or an MFA challenge, so every field is optional and
 * [toResult] decides which shape arrived.
 */
@Serializable
internal data class NativeAuthResponse(
    val ok: Boolean = false,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String? = "Bearer",
    val scope: String? = null,
    @SerialName("mfa_required") val mfaRequired: Boolean = false,
    val challenge: String? = null,
    val message: String? = null,
    val user: NativeUser? = null,
    val error: String? = null,
) {
    fun toResult(): NativeAuthResult {
        val challenge = challenge?.takeIf { it.isNotBlank() }
        if (mfaRequired || (challenge != null && accessToken.isNullOrBlank())) {
            return NativeAuthResult.MfaRequired(
                challenge = challenge
                    ?: throw MailOAuthException(false, "MFA required but no challenge was returned"),
                expiresIn = (expiresIn ?: 0L).toInt(),
                message = message,
            )
        }
        val access = accessToken?.takeIf { it.isNotBlank() }
            ?: throw MailOAuthException(false, "Sign-in response is missing an access token")
        val lifetime = expiresIn ?: 0L
        if (lifetime !in 1..86_400) {
            throw MailOAuthException(false, "Sign-in response carries an invalid lifetime")
        }
        return NativeAuthResult.Tokens(
            tokens = TokenResponse(
                accessToken = access,
                refreshToken = refreshToken?.takeIf { it.isNotBlank() },
                expiresIn = lifetime,
                tokenType = tokenType ?: "Bearer",
                scope = scope,
            ),
            user = user,
        )
    }
}

/** `POST /api/mobile/auth/peer-code` — asks Accounts for a one-time code for a sibling app. */
@Serializable
data class ApiPeerCodeRequest(
    @SerialName("target_client_id") val targetClientId: String,
    @SerialName("redirect_uri") val redirectUri: String,
    val scope: String,
    @SerialName("code_challenge") val codeChallenge: String,
    @SerialName("code_challenge_method") val codeChallengeMethod: String = "S256",
)

@Serializable
data class ApiPeerCodeResponse(
    val ok: Boolean = false,
    val code: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("redirect_uri") val redirectUri: String? = null,
    val scope: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 0,
    val error: String? = null,
)
