package com.gothwad.mail.auth

import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Direct native authentication against `https://accounts.bansify.com/api/mobile/auth/{endpoint}`.
 *
 * This is the primary sign-in path: it needs no browser, no Custom Tabs and no redirect, so it
 * works on a device where Chrome (or any browser) is missing. The OAuth 2.0 + PKCE browser flow
 * remains available as a secondary option and both paths hand back the same [TokenResponse].
 */
interface NativeAuthService {
    suspend fun checkIdentifier(identifier: String): ApiCheckIdentifierResult
    suspend fun checkUsername(username: String): ApiCheckUsernameResult
    suspend fun signIn(identifier: String, password: String): NativeAuthResult
    suspend fun verifyMfa(challenge: String, code: String): NativeAuthResult
    suspend fun signUp(request: ApiNativeSignUpRequest): NativeAuthResult
    suspend fun forgotPassword(username: String): ApiNativeOkResult
    suspend fun resetPassword(token: String, password: String): ApiNativeOkResult
}

/** OkHttp implementation bound to the Accounts host only. */
class NativeAuthClient(
    private val config: AuthConfig = AuthConfig.DEFAULT,
    private val client: OkHttpClient = MailOAuthClient.defaultClient(),
) : NativeAuthService {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val base = config.accountsBaseUrl.trimEnd('/')
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun checkIdentifier(identifier: String): ApiCheckIdentifierResult = decode(
        post(
            "/mobile/auth/check-identifier",
            json.encodeToString(ApiCheckIdentifierRequest.serializer(), ApiCheckIdentifierRequest(identifier)),
        ),
    )

    override suspend fun checkUsername(username: String): ApiCheckUsernameResult = decode(
        post(
            "/mobile/auth/check-username",
            json.encodeToString(ApiCheckUsernameRequest.serializer(), ApiCheckUsernameRequest(username)),
        ),
    )

    override suspend fun signIn(identifier: String, password: String): NativeAuthResult {
        val body = ApiNativeSignInRequest(
            clientId = config.clientId,
            scope = config.scopes.sorted().joinToString(" "),
            identifier = identifier,
            password = password,
        )
        return postNative("/mobile/auth/signin", json.encodeToString(ApiNativeSignInRequest.serializer(), body))
    }

    override suspend fun verifyMfa(challenge: String, code: String): NativeAuthResult {
        val body = ApiNativeMfaRequest(
            clientId = config.clientId,
            scope = config.scopes.sorted().joinToString(" "),
            challenge = challenge,
            code = code,
        )
        return postNative("/mobile/auth/signin/mfa", json.encodeToString(ApiNativeMfaRequest.serializer(), body))
    }

    override suspend fun signUp(request: ApiNativeSignUpRequest): NativeAuthResult {
        val payload = request.copy(clientId = config.clientId, scope = config.scopes.sorted().joinToString(" "))
        return postNative(
            "/mobile/auth/signup",
            json.encodeToString(ApiNativeSignUpRequest.serializer(), payload),
            expected = setOf(200, 201),
        )
    }

    override suspend fun forgotPassword(username: String): ApiNativeOkResult = decode(
        post(
            "/mobile/auth/forgot-password",
            json.encodeToString(ApiNativeForgotPasswordRequest.serializer(), ApiNativeForgotPasswordRequest(username)),
        ),
    )

    override suspend fun resetPassword(token: String, password: String): ApiNativeOkResult = decode(
        post(
            "/mobile/auth/reset-password",
            json.encodeToString(ApiNativeResetPasswordRequest.serializer(), ApiNativeResetPasswordRequest(token, password)),
        ),
    )

    // --- Internals -----------------------------------------------------------

    private inline fun <reified T> decode(raw: String): T =
        runCatching { json.decodeFromString<T>(raw) }
            .getOrElse { throw MailOAuthException(false, "Bansify Accounts returned an unreadable response", it) }

    private fun post(path: String, payload: String): String =
        execute("$base$path", payload, expected = setOf(200, 201))

    private suspend fun postNative(
        path: String,
        payload: String,
        expected: Set<Int> = setOf(200, 201, 202),
    ): NativeAuthResult {
        val raw = execute("$base$path", payload, expected)
        val response = runCatching { json.decodeFromString<NativeAuthResponse>(raw) }.getOrElse {
            throw MailOAuthException(false, "Bansify Accounts returned an unreadable response", it)
        }
        return response.toResult()
    }

    private fun execute(url: String, payload: String, expected: Set<Int>): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw MailOAuthException(false, "Could not reach Bansify Accounts", error)
        }
        return response.use { result ->
            val body = result.body?.string().orEmpty()
            if (result.code !in expected) {
                throw MailOAuthException(rejected = result.code == 401 || result.code == 403, message = errorMessage(body, result.code))
            }
            body
        }
    }

    /** Prefers the server's `error` field so the UI can show the real reason. */
    private fun errorMessage(body: String, status: Int): String {
        val parsed = runCatching {
            json.parseToJsonElement(body).let { element ->
                (element as? kotlinx.serialization.json.JsonObject)?.get("error")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() } ?: "Bansify Accounts request failed with HTTP $status"
    }
}
