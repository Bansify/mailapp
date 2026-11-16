package com.gothwad.mail.data.remote

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the Mail Worker client contract against a real HTTP server: bearer scoping, the
 * `If-None-Match` / `304` flow, error mapping and the send/draft/trash endpoints.
 */
class MailApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MailApiClient
    private val tokenCalls = AtomicInteger()
    private var token: String? = "test-token"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/api").toString().trimEnd('/')
        val provider = MailAccessTokenProvider {
            tokenCalls.incrementAndGet()
            token
        }
        val http = OkHttpClient.Builder().build()
        client = MailApiClient(
            tokenProvider = provider,
            baseUrl = base,
            client = http,
        )
        // The same-origin guard must accept the loopback test server exactly as configured.
        assertTrue(base.startsWith("http://localhost"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `every message request carries the bearer token and Accept header`() = runTest {
        server.enqueue(json("""{"messages":[],"folder":"inbox","nextBefore":null}"""))

        client.messages(folder = "inbox", limit = 25)

        val request = server.takeRequest()
        assertEquals("/api/mail/messages?folder=inbox&limit=25", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("GET", request.method)
    }

    @Test
    fun `missing credential fails before any request is sent`() = runTest {
        token = null
        val result = runCatching { client.messages(folder = "inbox") }

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as MailApiException
        assertEquals(401, error.statusCode)
        assertTrue(error.isUnauthenticated)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `folder mapping sends the documented server folder values`() = runTest {
        listOf("inbox", "starred", "sent", "drafts", "trash").forEach { folder ->
            server.enqueue(json("""{"messages":[],"folder":"$folder","nextBefore":null}"""))
            client.messages(folder = folder)
            assertEquals("/api/mail/messages?folder=$folder&limit=50", server.takeRequest().path)
        }
    }

    @Test
    fun `search sends q and the filter flags`() = runTest {
        server.enqueue(json("""{"messages":[],"folder":"inbox","nextBefore":null}"""))

        client.messages(
            folder = "inbox",
            query = "invoice march",
            unreadOnly = true,
            starredOnly = true,
            hasAttachment = true,
        )

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.contains("q=invoice%20march") || path.contains("q=invoice+march"))
        assertTrue(path.contains("unread=1"))
        assertTrue(path.contains("starred=1"))
        assertTrue(path.contains("hasAttachment=1"))
    }

    @Test
    fun `message detail sends If-None-Match and maps 304 to not modified`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(304).addHeader("ETag", "\"v1\""),
        )

        val detail = client.message("msg-1", etag = "\"v1\"")

        val request = server.takeRequest()
        assertEquals("/api/mail/messages/msg-1", request.path)
        assertEquals("\"v1\"", request.getHeader("If-None-Match"))
        assertTrue(detail.notModified)
        assertEquals("\"v1\"", detail.etag)
        assertEquals("", detail.message.bodyText.orEmpty())
    }

    @Test
    fun `message detail stores the returned body and new ETag on 200`() = runTest {
        server.enqueue(
            json(
                """{"message":{"id":"msg-1","folder":"inbox","from":{"name":"Ananya","email":"ananya@gothwadtech.com"},
                   "to":["pawan@gothwadtech.com"],"cc":[],"bcc":[],"subject":"Hello","snippet":"Hi",
                   "receivedAt":"2026-01-02T03:04:05.000Z","isRead":true,"isStarred":false,"attachments":[],
                   "bodyText":"Full body","bodyHtml":"<p>Full body</p>"}}""",
            ).addHeader("ETag", "\"v2\""),
        )

        val detail = client.message("msg-1", etag = "\"v1\"")

        assertFalse(detail.notModified)
        assertEquals("Full body", detail.message.bodyText)
        assertEquals("<p>Full body</p>", detail.message.bodyHtml)
        assertEquals("\"v2\"", detail.etag)
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `patch message maps the returned message`() = runTest {
        server.enqueue(
            json("""{"message":{"id":"msg-1","folder":"inbox","isRead":true,"isStarred":true}}"""),
        )

        val message = client.patchMessage("msg-1", ApiMessagePatch(isStarred = true))

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/mail/messages/msg-1", request.path)
        assertTrue(request.body.readUtf8().contains("\"isStarred\":true"))
        assertTrue(message.isStarred)
    }

    @Test
    fun `move to trash and restore use the documented patch fields`() = runTest {
        server.enqueue(json("""{"message":{"id":"m1","folder":"trash"}}"""))
        client.patchMessage("m1", ApiMessagePatch(moveToTrash = true))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"moveToTrash\":true"))

        server.enqueue(json("""{"message":{"id":"m1","folder":"inbox"}}"""))
        client.patchMessage("m1", ApiMessagePatch(moveToInbox = true))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"moveToInbox\":true"))
    }

    @Test
    fun `batch mutation posts ids and the requested flags`() = runTest {
        server.enqueue(json("""{"ok":true,"updated":3}"""))

        val result = client.batchMessages(
            ApiMessageBatch(ids = listOf("a", "b", "c"), isRead = true),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mail/messages/batch", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"ids\":[\"a\",\"b\",\"c\"]"))
        assertTrue(body.contains("\"isRead\":true"))
        assertEquals(3, result.updated)
        assertTrue(result.ok)
    }

    @Test
    fun `saving a draft posts the server draft id and recipients`() = runTest {
        server.enqueue(
            json("""{"message":{"id":"draft-1","folder":"drafts","to":["a@b.com"],"subject":"Hi"}}"""),
        )

        val draft = client.saveDraft(
            ApiDraftBody(
                id = "draft-1",
                to = listOf("a@b.com"),
                cc = emptyList(),
                bcc = emptyList(),
                subject = "Hi",
                body = "Body",
            ),
        )

        val request = server.takeRequest()
        assertEquals("/api/mail/drafts", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.body.readUtf8().contains("\"id\":\"draft-1\""))
        assertEquals("draft-1", draft.id)
    }

    @Test
    fun `sending a message posts the draft id and returns the remaining quota`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"id":"sent-1","sent":true,"saved":true,"remainingToday":7}"""),
        )

        val result = client.send(
            ApiSendBody(
                to = listOf("a@b.com"),
                cc = emptyList(),
                bcc = emptyList(),
                subject = "Hi",
                body = "Body",
                draftId = "draft-1",
            ),
        )

        val request = server.takeRequest()
        assertEquals("/api/mail/send", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"draftId\":\"draft-1\""))
        assertTrue(body.contains("\"bcc\":[]"))
        assertTrue(result.sent)
        assertEquals(7, result.remainingToday)
    }

    @Test
    fun `daily limit maps to a rate limited error with a user facing message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"error":"daily_limit"}"""),
        )

        val error = runCatching { client.send(ApiSendBody(to = listOf("a@b.com"), subject = "s", body = "b")) }
            .exceptionOrNull() as MailApiException

        assertEquals(429, error.statusCode)
        assertEquals(MailApiErrorCode.DAILY_LIMIT, error.code)
        assertTrue(error.isRateLimited)
        assertEquals("Daily send limit reached — try again tomorrow", sendFailureMessage(error))
    }

    @Test
    fun `sending not configured maps to an explicit message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"error":"sending_not_configured"}"""),
        )

        val error = runCatching { client.send(ApiSendBody(subject = "s", body = "b")) }.exceptionOrNull()

        assertTrue(error is MailApiException)
        assertEquals(
            "Sending is not configured for this account yet",
            sendFailureMessage(error as MailApiException),
        )
    }

    @Test
    fun `delete message and empty trash use DELETE`() = runTest {
        server.enqueue(json("""{"ok":true}"""))
        assertTrue(client.deleteMessage("msg-1").ok)
        var request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mail/messages/msg-1", request.path)

        server.enqueue(json("""{"ok":true,"deleted":4}"""))
        val emptied = client.emptyTrash()
        request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mail/trash", request.path)
        assertEquals(4, emptied.deleted)
    }

    @Test
    fun `delete draft uses the drafts endpoint`() = runTest {
        server.enqueue(json("""{"ok":true}"""))

        client.deleteDraft("draft-9")

        assertEquals("/api/mail/drafts/draft-9", server.takeRequest().path)
    }

    @Test
    fun `settings read and retention patch round trip`() = runTest {
        server.enqueue(
            json(
                """{"retentionLimit":500,"storageQuotaBytes":52428800,"storageUsedBytes":1048576,
                   "totalMessages":12,"starredMessages":2}""",
            ),
        )
        val settings = client.mailSettings()
        assertEquals(500, settings.retentionLimit)
        assertEquals(52_428_800L, settings.storageQuotaBytes)
        assertEquals(1_048_576L, settings.storageUsedBytes)
        assertEquals("1.0 MB", formatStorageBytes(settings.storageUsedBytes))
        assertEquals("/api/mail/settings", server.takeRequest().path)

        server.enqueue(
            json(
                """{"retentionLimit":1500,"storageQuotaBytes":52428800,"storageUsedBytes":1048576,
                   "totalMessages":12,"starredMessages":2}""",
            ),
        )
        val updated = client.updateMailSettings(1500)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertTrue(request.body.readUtf8().contains("\"retentionLimit\":1500"))
        assertEquals(1500, updated.retentionLimit)
    }

    @Test
    fun `invalid retention value is rejected locally`() = runTest {
        assertTrue(runCatching { client.updateMailSettings(999) }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `session probe reads identity without a bearer token`() = runTest {
        server.enqueue(
            json(
                """{"authenticated":true,"user":{"username":"pawan","email":"pawan@gothwadtech.com",
                   "name":"Pawan Gothwad"},"unread":{"pawan":3},
                   "limits":{"dailySendLimit":20,"sendRemainingToday":18}}""",
            ),
        )

        val session = client.session()

        val request = server.takeRequest()
        assertEquals("/api/session", request.path)
        assertNull("The session probe must not send a bearer token", request.getHeader("Authorization"))
        assertEquals("pawan", session.user.username)
        assertEquals(3, session.unread["pawan"])
        assertEquals(18, session.limits.sendRemainingToday)
    }

    @Test
    fun `unauthorized response maps to an unauthenticated error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthenticated"}"""))

        val error = runCatching { client.mailSettings() }.exceptionOrNull() as MailApiException
        assertTrue(error.isUnauthenticated)
        assertEquals(401, error.statusCode)
    }

    @Test
    fun `cross host request is refused before sending the token`() {
        val base = "https://mail.gothwadtech.com/api".toHttpUrl()
        val other = "https://evil.example.com/api/mail/messages".toHttpUrl()
        val failure = runCatching { MailApiClient.assertSameHost(base, other) }
        assertTrue(failure.isFailure)
        // Same-origin requests pass.
        MailApiClient.assertSameHost(base, base.newBuilder().addPathSegment("mail").build())
    }

    @Test
    fun `storage labels convert bytes into MB and GB`() {
        assertEquals("0 MB", formatStorageBytes(0))
        assertEquals("512 KB", formatStorageBytes(512 * 1024L))
        assertEquals("50.0 MB", formatStorageBytes(52_428_800L))
        assertEquals("1.5 GB", formatStorageBytes(1_610_612_736L))
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}
