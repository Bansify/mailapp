package com.gothwad.mail.auth

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Wire form of `POST /oauth/token`. */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0L,
    @SerialName("token_type") val tokenType: String = "Bearer",
    val scope: String? = null,
)

/** Result of `POST /oauth/revoke`. */
enum class RevokeStatus { Revoked, InvalidGrant, Failed }

/**
 * OAuth boundary used by [MailAuthSessionManager]; implemented by [MailOAuthClient] in production
 * and by fakes in unit tests.
 */
interface MailOAuthService {
    fun exchangeAuthorizationCode(code: String, codeVerifier: String, redirectUri: String): TokenResponse
    fun refreshToken(refreshToken: String): TokenResponse
    fun userInfo(accessToken: String): GothwadUserInfo
    fun revoke(refreshToken: String?, accessToken: String?): RevokeStatus

    /**
     * Exchanges a peer-issued one-time PKCE code for this app's own token pair
     * (`POST /api/mobile/auth/peer-code` equivalent, redeemed through the token endpoint).
     */
    fun issuePeerAuthCode(
        peerAccessToken: String,
        targetClientId: String,
        redirectUri: String,
        scope: String,
        codeChallenge: String,
    ): String? = null
}

/**
 * Native OAuth 2.0 + PKCE client for Bansify Accounts.
 *
 * Only the Accounts host is contacted here, and no redirect following is allowed so an
 * authorization response can never bounce to an unexpected origin.
 */
class MailOAuthClient(
    private val config: AuthConfig = AuthConfig.DEFAULT,
    private val client: OkHttpClient = defaultClient(),
) : MailOAuthService {

    private val json = Json { ignoreUnknownKeys = true }
    private val base = config.accountsBaseUrl.trimEnd('/')

    override fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): TokenResponse = requestToken(
        "grant_type" to "authorization_code",
        "code" to code,
        "code_verifier" to codeVerifier,
        "redirect_uri" to redirectUri,
    )

    override fun refreshToken(refreshToken: String): TokenResponse =
        requestToken("grant_type" to "refresh_token", "refresh_token" to refreshToken)

    override fun userInfo(accessToken: String): GothwadUserInfo {
        val request = Request.Builder()
            .url("$base/oauth/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        val body = execute(request)
        val info = runCatching { json.decodeFromString<GothwadUserInfo>(body) }
            .getOrElse { throw MailOAuthException(false, "UserInfo response was not valid JSON", it) }
        if (info.userId.isBlank()) throw MailOAuthException(false, "UserInfo response has no subject")
        return info
    }

    /**
     * Explicit server-side grant revoke. Requires a bearer access token plus the form
     * `client_id` and `token` fields, exactly like the Accounts `oauth-native-revoke` handler.
     */
    override fun revoke(refreshToken: String?, accessToken: String?): RevokeStatus {
        val bearer = accessToken?.takeIf { it.isNotBlank() } ?: return RevokeStatus.Failed
        val target = refreshToken?.takeIf { it.isNotBlank() } ?: bearer
        val hint = if (!refreshToken.isNullOrBlank()) "refresh_token" else "access_token"
        val form = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("token", target)
            .add("token_type_hint", hint)
            .build()
        val request = Request.Builder()
            .url("$base/oauth/revoke")
            .header("Authorization", "Bearer $bearer")
            .post(form)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> RevokeStatus.Revoked
                    response.code == 400 || response.code == 401 -> RevokeStatus.InvalidGrant
                    else -> RevokeStatus.Failed
                }
            }
        }.getOrDefault(RevokeStatus.Failed)
    }

    /**
     * Asks this app's own Accounts session for a one-time code for a sibling app.
     *
     * Used when Bansify Mail is the peer that already holds a session and another first-party app
     * requests silent sign-in. The access token never leaves the process.
     */
    override fun issuePeerAuthCode(
        peerAccessToken: String,
        targetClientId: String,
        redirectUri: String,
        scope: String,
        codeChallenge: String,
    ): String? {
        // Explicit type: `encodeToString` needs the serializer, which the reified overload of
        // `Json.encodeToString(value)` cannot infer from a lambda-wrapped argument.
        val payload: String = json.encodeToString(
            ApiPeerCodeRequest.serializer(),
            ApiPeerCodeRequest(
                targetClientId = targetClientId,
                redirectUri = redirectUri,
                scope = scope,
                codeChallenge = codeChallenge,
                codeChallengeMethod = "S256",
            ),
        )
        val request = Request.Builder()
            .url("$base/mobile/auth/peer-code")
            .header("Authorization", "Bearer $peerAccessToken")
            .header("Accept", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use null
                json.decodeFromString<ApiPeerCodeResponse>(body).code?.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(null)
    }

    private fun requestToken(vararg fields: Pair<String, String>): TokenResponse {
        val form = FormBody.Builder().add("client_id", config.clientId)
        fields.forEach { (key, value) -> form.add(key, value) }
        val request = Request.Builder()
            .url("$base/oauth/token")
            .post(form.build())
            .header("Accept", "application/json")
            .build()
        val response = execute(request)
        val tokens = runCatching { json.decodeFromString<TokenResponse>(response) }
            .getOrElse { throw MailOAuthException(false, "Token response was not valid JSON", it) }
        if (!tokens.tokenType.equals("Bearer", ignoreCase = true) || tokens.accessToken.isBlank()) {
            throw MailOAuthException(false, "Token response is missing a bearer access token")
        }
        if (tokens.expiresIn !in 1..86_400) {
            throw MailOAuthException(false, "Token response carries an invalid lifetime")
        }
        return tokens
    }

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body?.string() ?: throw MailOAuthException(false, "Empty OAuth response")
        if (!response.isSuccessful) {
            val errorCode = runCatching {
                json.parseToJsonElement(body).let { element ->
                    (element as? kotlinx.serialization.json.JsonObject)
                        ?.get("error")
                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                }
            }.getOrNull()
            val rejected = errorCode == "invalid_grant" || errorCode == "invalid_token" ||
                response.code == 401 && errorCode != null
            throw MailOAuthException(
                rejected = rejected,
                message = "OAuth request failed with HTTP ${response.code}${errorCode?.let { " ($it)" } ?: "" }",
            )
        }
        body
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Authorization request builder shared by the sign-in screen and the account switcher. */
object AuthorizeRequest {

    /**
     * Builds `GET /api/oauth/authorize` for [pending].
     *
     * Parameters are always ordered and encoded the same way so the value can be unit tested.
     */
    fun authorizeUrl(
        pending: PendingLogin,
        loginHint: String? = null,
        config: AuthConfig = AuthConfig.DEFAULT,
    ): String {
        val base = config.accountsBaseUrl.trimEnd('/')
        val query = buildList {
            add("response_type" to "code")
            add("client_id" to config.clientId)
            add("redirect_uri" to pending.redirectUri)
            add("scope" to config.scopes.sorted().joinToString(" "))
            add("state" to pending.state)
            add("code_challenge" to Pkce.challenge(pending.verifier))
            add("code_challenge_method" to "S256")
            val hint = loginHint?.trim()?.takeIf { it.isNotEmpty() } ?: pending.loginHint
            if (!hint.isNullOrBlank()) add("login_hint" to hint)
        }.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return "$base/oauth/authorize?$query"
    }

    /** Builds the Accounts "choose an account" page that wraps [authorizeUrl]. */
    fun chooseAccountUrl(
        authorizeUrl: String,
        config: AuthConfig = AuthConfig.DEFAULT,
    ): String {
        // The chooser is a web page on the site root; only the OAuth endpoints live under /api.
        val base = config.accountsBaseUrl.trimEnd('/').removeSuffix("/api")
        return "$base/signin?choose=1&next=${encode(authorizeUrl)}"
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
