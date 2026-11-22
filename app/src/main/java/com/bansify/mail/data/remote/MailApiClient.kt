package com.bansify.mail.data.remote

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Supplies the bearer token for the active account, refreshing it when needed. */
fun interface MailAccessTokenProvider {
    /** Returns a valid access token, or `null` when nobody is signed in. */
    suspend fun accessToken(): String?
}

/**
 * Client for `https://mail.bansify.com/api`.
 *
 * Security rules enforced here:
 * - The `Authorization: Bearer` header is attached only to [baseUrl]'s host, never to any other
 *   host and never to attachment or image URLs.
 * - Redirect following is disabled, so a 30x can never move the request (and the token) elsewhere.
 * - `Accept: application/json` is always sent.
 */
class MailApiClient(
    private val tokenProvider: MailAccessTokenProvider,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // --- Session & settings ---------------------------------------------------

    suspend fun session(): ApiSession = execute(
        path("session"),
        method = "GET",
    ) { body -> json.decodeFromString<ApiSession>(body) }

    suspend fun mailSettings(): ApiMailSettings = execute(
        path("mail", "settings"),
        method = "GET",
    ) { body -> json.decodeFromString<ApiMailSettings>(body) }

    suspend fun updateMailSettings(retentionLimit: Int): ApiMailSettings {
        require(retentionLimit in ApiMailSettings.VALID_RETENTION_LIMITS) {
            "retentionLimit must be 500, 1500 or 0"
        }
        val payload = json.encodeToString(ApiSettingsPatch.serializer(), ApiSettingsPatch(retentionLimit))
        return execute(path("mail", "settings"), method = "PATCH", body = payload.toRequestBody(jsonMediaType)) { body ->
            json.decodeFromString<ApiMailSettings>(body)
        }
    }

    // --- Messages -------------------------------------------------------------

    /** Lists one folder. [before] paginates backwards, [query] performs a server-side search. */
    suspend fun messages(
        folder: String,
        limit: Int = 50,
        before: String? = null,
        query: String? = null,
        unreadOnly: Boolean = false,
        starredOnly: Boolean = false,
        hasAttachment: Boolean = false,
    ): ApiMessageList {
        val url = path("mail", "messages").newBuilder()
            .addQueryParameter("folder", folder)
            .addQueryParameter("limit", limit.toString())
            .apply {
                before?.takeIf { it.isNotBlank() }?.let { addQueryParameter("before", it) }
                query?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("q", it) }
                if (unreadOnly) addQueryParameter("unread", "1")
                if (starredOnly) addQueryParameter("starred", "1")
                if (hasAttachment) addQueryParameter("hasAttachment", "1")
            }
            .build()
        return execute(url, method = "GET") { body -> json.decodeFromString<ApiMessageList>(body) }
    }

    /**
     * Loads one message. When [etag] is supplied it is sent as `If-None-Match`, and a `304` answer
     * is reported as [MailMessageDetail.notModified] with no body re-download.
     */
    suspend fun message(id: String, etag: String? = null): MailMessageDetail {
        val url = path("mail", "messages", encodeSegment(id))
        return executeRaw(url, method = "GET", ifNoneMatch = etag) { response ->
            if (response.code == HTTP_NOT_MODIFIED) {
                MailMessageDetail(message = ApiMessage(id = id), etag = etag, notModified = true)
            } else {
                val body = response.body?.string().orEmpty()
                val envelope = json.decodeFromString<ApiMessageEnvelope>(body)
                MailMessageDetail(
                    message = envelope.message,
                    etag = response.header("ETag") ?: etag,
                    notModified = false,
                )
            }
        }
    }

    suspend fun patchMessage(id: String, patch: ApiMessagePatch): ApiMessage {
        val payload = json.encodeToString(ApiMessagePatch.serializer(), patch)
        return execute(
            path("mail", "messages", encodeSegment(id)),
            method = "PATCH",
            body = payload.toRequestBody(jsonMediaType),
        ) { body -> json.decodeFromString<ApiMessageEnvelope>(body).message }
    }

    suspend fun batchMessages(batch: ApiMessageBatch): ApiBatchResult {
        val payload = json.encodeToString(ApiMessageBatch.serializer(), batch)
        return execute(
            path("mail", "messages", "batch"),
            method = "POST",
            body = payload.toRequestBody(jsonMediaType),
        ) { body -> json.decodeFromString<ApiBatchResult>(body) }
    }

    suspend fun deleteMessage(id: String): ApiOkResult = execute(
        path("mail", "messages", encodeSegment(id)),
        method = "DELETE",
    ) { body -> json.decodeFromString<ApiOkResult>(body) }

    suspend fun emptyTrash(): ApiOkResult = execute(
        path("mail", "trash"),
        method = "DELETE",
    ) { body -> json.decodeFromString<ApiOkResult>(body) }

    // --- Drafts & sending -----------------------------------------------------

    suspend fun saveDraft(draft: ApiDraftBody): ApiMessage {
        val payload = json.encodeToString(ApiDraftBody.serializer(), draft)
        return execute(
            path("mail", "drafts"),
            method = "POST",
            body = payload.toRequestBody(jsonMediaType),
        ) { body -> json.decodeFromString<ApiMessageEnvelope>(body).message }
    }

    suspend fun deleteDraft(id: String): ApiOkResult = execute(
        path("mail", "drafts", encodeSegment(id)),
        method = "DELETE",
    ) { body -> json.decodeFromString<ApiOkResult>(body) }

    suspend fun send(body: ApiSendBody): ApiSendResult {
        val payload = json.encodeToString(ApiSendBody.serializer(), body)
        return execute(
            path("mail", "send"),
            method = "POST",
            body = payload.toRequestBody(jsonMediaType),
            expectedSuccessCodes = setOf(200, 201),
        ) { raw -> json.decodeFromString<ApiSendResult>(raw) }
    }

    // --- Internals -----------------------------------------------------------

    private fun path(vararg segments: String): HttpUrl {
        val builder = base.newBuilder()
        segments.forEach { segment -> builder.addPathSegment(segment) }
        return builder.build()
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private suspend fun <T> execute(
        url: HttpUrl,
        method: String,
        body: RequestBody? = null,
        expectedSuccessCodes: Set<Int> = setOf(200, 201, 204),
        ifNoneMatch: String? = null,
        parse: (String) -> T,
    ): T = executeRaw(url, method, body, expectedSuccessCodes, ifNoneMatch) { response ->
        parse(response.body?.string().orEmpty())
    }

    private suspend fun <T> executeRaw(
        url: HttpUrl,
        method: String,
        body: RequestBody? = null,
        expectedSuccessCodes: Set<Int> = setOf(200, 201, 204, 304),
        ifNoneMatch: String? = null,
        parse: (Response) -> T,
    ): T {
        assertSameHost(base, url)
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        if (ifNoneMatch != null && ifNoneMatch.isNotBlank()) {
            builder.header("If-None-Match", ifNoneMatch)
        }
        // The base URL is the only host this client ever talks to, and it is the only host that
        // ever receives the bearer token: attachment and image URLs bypass this client entirely.
        val token = tokenProvider.accessToken()
            ?: throw MailApiException(401, MailApiErrorCode.UNAUTHENTICATED, "Not signed in to Bansify Mail")
        builder.header("Authorization", "Bearer $token")
        val request = builder.method(method, body).build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw error
        }
        return response.use { result ->
            if (result.code !in expectedSuccessCodes) {
                throw toApiException(result)
            }
            parse(result)
        }
    }

    private fun toApiException(response: Response): MailApiException {
        val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        val code = runCatching {
            val element = json.parseToJsonElement(raw)
            val obj = element as? kotlinx.serialization.json.JsonObject
            (obj?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (obj?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.getOrNull()
            ?: when (response.code) {
                401 -> MailApiErrorCode.UNAUTHENTICATED
                404 -> MailApiErrorCode.NOT_FOUND
                429 -> MailApiErrorCode.DAILY_LIMIT
                else -> "http_${response.code}"
            }
        val retryAfter = response.header("Retry-After")?.toIntOrNull()
        return MailApiException(
            statusCode = response.code,
            code = code,
            message = "Bansify Mail request failed with HTTP ${response.code} ($code)",
            retryAfterSeconds = retryAfter,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://mail.bansify.com/api"
        const val GothwadMailHost: String = "mail.bansify.com"
        const val HTTP_NOT_MODIFIED: Int = 304

        /**
         * Refuses any request that is not aimed at the configured Mail API origin, so a malformed
         * or attacker-influenced URL can never carry the bearer token to another host.
         */
        fun assertSameHost(base: HttpUrl, url: HttpUrl) {
            require(url.scheme == base.scheme && url.host == base.host && url.port == base.port) {
                "Refusing to send a Bansify Mail request to ${url.host}"
            }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Never follow a redirect with a bearer token attached.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
