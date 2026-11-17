package com.gothwad.mail.data.repository

import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.data.model.ServerFolder
import com.gothwad.mail.data.remote.MailAccessTokenProvider
import com.gothwad.mail.data.remote.MailApiClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end repository behaviour against a stub Mail Worker: per-account Room isolation, folder
 * mapping, ETag reuse, optimistic mutations and the send/draft/trash API mapping.
 */
class MailRepositorySyncTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MailApiClient
    private lateinit var dao: FakeMailDao
    private lateinit var repository: MailRepository

    private val accountA = MailAccountEntity(
        email = "pawan@gothwadtech.com",
        userId = "user-a",
        username = "pawan",
        displayName = "Pawan Gothwad",
        avatarColorHex = 0xFF0B57D0,
        isActive = true,
    )
    private val accountB = MailAccountEntity(
        email = "ananya@gothwadtech.com",
        userId = "user-b",
        username = "ananya",
        displayName = "Ananya Verma",
        avatarColorHex = 0xFF146C2E,
        isActive = false,
    )

    /** Records the requests the repository makes so the API mapping can be asserted. */
    private val recorded = mutableListOf<RecordedRequest>()
    private var detailResponse: MockResponse? = null
    private var detailAlwaysNotModified: Boolean = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded.add(request)
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/mail/messages/") && request.method == "GET" &&
                        detailAlwaysNotModified -> MockResponse().setResponseCode(304)
                        .addHeader("ETag", request.getHeader("If-None-Match") ?: "\"etag-1\"")
                    path.startsWith("/api/mail/messages/") && request.method == "GET" ->
                        detailResponse ?: json(
                            """{"message":{"id":"msg-1","folder":"inbox","from":{"name":"Ananya","email":"ananya@gothwadtech.com"},
                               "to":["pawan@gothwadtech.com"],"cc":[],"bcc":[],"subject":"Hello","snippet":"Hi",
                               "receivedAt":"2026-01-02T03:04:05.000Z","isRead":false,"isStarred":false,
                               "attachments":[],"bodyText":"Full body text","bodyHtml":null}}""",
                        ).addHeader("ETag", "\"etag-2\"")
                    path.startsWith("/api/mail/messages/batch") -> json("""{"ok":true,"updated":2}""")
                    path.startsWith("/api/mail/messages/") && request.method == "PATCH" ->
                        json("""{"message":{"id":"msg-1","folder":"inbox","isRead":true}}""")
                    path.startsWith("/api/mail/messages/") && request.method == "DELETE" -> json("""{"ok":true}""")
                    path.startsWith("/api/mail/messages?folder=inbox") -> json(inboxPageJson)
                    path.startsWith("/api/mail/messages?folder=sent") -> json(
                        """{"messages":[],"folder":"sent","nextBefore":null}""",
                    )
                    path.startsWith("/api/mail/messages?folder=trash") -> json(
                        """{"messages":[],"folder":"trash","nextBefore":null}""",
                    )
                    path.startsWith("/api/mail/settings") && request.method == "GET" -> json(settingsJson)
                    path.startsWith("/api/mail/settings") && request.method == "PATCH" -> json(settingsJson)
                    path == "/api/session" -> json(sessionJson)
                    path == "/api/mail/send" -> MockResponse().setResponseCode(201)
                        .setBody("""{"id":"sent-1","sent":true,"saved":true,"remainingToday":9}""")
                    path == "/api/mail/drafts" -> json(
                        """{"message":{"id":"draft-1","folder":"drafts","to":["b@c.com"],"subject":"Draft"}}""",
                    )
                    path == "/api/mail/drafts/draft-1" -> json("""{"ok":true}""")
                    path == "/api/mail/trash" && request.method == "DELETE" -> json("""{"ok":true,"deleted":3}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}""")
                }
            }
        }
        server.start()
        api = MailApiClient(
            tokenProvider = MailAccessTokenProvider { "token-a" },
            baseUrl = server.url("/api").toString().trimEnd('/'),
            client = OkHttpClient.Builder().build(),
        )
        dao = FakeMailDao()
        repository = MailRepository(dao, api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `folder refresh upserts messages for the active account only`() = runTest {
        repository.upsertAccount(accountA)

        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)

        val cached = dao.getThreadsForAccount(accountA.email)
        assertEquals(2, cached.size)
        assertTrue(cached.all { it.accountEmail == accountA.email })
        assertTrue(cached.all { it.folder == MailFolder.PRIMARY })
        assertEquals("msg-1", cached.first { it.serverId == "msg-1" }.serverId)
    }

    @Test
    fun `account switching never leaks threads across accounts`() = runTest {
        repository.upsertAccount(accountA)
        repository.upsertAccount(accountB)
        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)

        assertTrue("Account B must start empty", dao.getThreadsForAccount(accountB.email).isEmpty())
        val observedForB = repository.observeThreadsForAccount(accountB.email).first()
        assertTrue(observedForB.isEmpty())

        repository.switchAccount(accountB.email)
        val active = dao.getActiveAccount()
        assertEquals(accountB.email, active?.email)
        assertTrue(dao.observeThreadsForAccount(active!!.email).first().isEmpty())

        repository.switchAccount(accountA.email)
        assertEquals(2, repository.observeThreadsForAccount(accountA.email).first().size)
    }

    @Test
    fun `signing out deletes every cached message for that account`() = runTest {
        repository.upsertAccount(accountA)
        repository.upsertAccount(accountB)
        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)
        dao.insertThread(
            com.gothwad.mail.data.model.EmailThreadEntity(
                id = "b::1",
                accountEmail = accountB.email,
                serverId = "1",
                subject = "B",
                snippet = "",
                primarySenderName = "B",
                primarySenderEmail = "b@c.com",
                primarySenderColorHex = 0,
                folder = MailFolder.PRIMARY,
            ),
        )

        repository.removeAccount(accountA.email)

        assertTrue(dao.getThreadsForAccount(accountA.email).isEmpty())
        assertEquals(1, dao.getThreadsForAccount(accountB.email).size)
        assertNull(dao.getAccount(accountA.email))
    }

    @Test
    fun `folder mapping follows the Mail Worker contract`() {
        assertEquals(ServerFolder.INBOX, ServerFolder.forMailFolder(MailFolder.ALL_INBOXES))
        assertEquals(ServerFolder.INBOX, ServerFolder.forMailFolder(MailFolder.PRIMARY))
        assertEquals(ServerFolder.INBOX, ServerFolder.forMailFolder(MailFolder.PROMOTIONS))
        assertEquals(ServerFolder.INBOX, ServerFolder.forMailFolder(MailFolder.SOCIAL))
        assertEquals(ServerFolder.INBOX, ServerFolder.forMailFolder(MailFolder.UPDATES))
        assertEquals(ServerFolder.INBOX, ServerFolder.forMailFolder(MailFolder.IMPORTANT))
        assertEquals(ServerFolder.INBOX, ServerFolder.forMailFolder(MailFolder.ALL_MAIL))
        assertEquals(ServerFolder.STARRED, ServerFolder.forMailFolder(MailFolder.STARRED))
        assertEquals(ServerFolder.SENT, ServerFolder.forMailFolder(MailFolder.SENT))
        assertEquals(ServerFolder.DRAFTS, ServerFolder.forMailFolder(MailFolder.DRAFT))
        assertEquals(ServerFolder.TRASH, ServerFolder.forMailFolder(MailFolder.TRASH))
    }

    @Test
    fun `trash folder refresh requests the trash mailbox`() = runTest {
        repository.upsertAccount(accountA)

        repository.refreshCurrentFolder(accountA, MailFolder.TRASH)

        assertTrue(recorded.any { it.path == "/api/mail/messages?folder=trash&limit=50" })
    }

    @Test
    fun `opening a message marks it read on the server and caches the body with its ETag`() = runTest {
        repository.upsertAccount(accountA)
        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)
        val thread = dao.getThreadByServerId(accountA.email, "msg-1")!!

        val opened = repository.openThread(accountA, thread)

        assertTrue(opened.bodyLoaded)
        assertEquals("Full body text", opened.bodyText)
        assertEquals("\"etag-2\"", opened.etag)
        assertTrue(
            "The read flag must be sent to the server",
            recorded.any { it.method == "PATCH" && it.path == "/api/mail/messages/msg-1" },
        )
        val cached = dao.getThreadByServerId(accountA.email, "msg-1")!!
        assertTrue(cached.isRead)
        assertEquals("\"etag-2\"", cached.etag)
    }

    @Test
    fun `a 304 response reuses the cached body instead of downloading it again`() = runTest {
        repository.upsertAccount(accountA)
        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)
        // A previously downloaded body with its ETag, but marked as not currently loaded.
        dao.updateBody(accountA.email, "msg-1", "cached body", null, "\"etag-1\"", "snippet")
        dao.updateThread(dao.getThreadByServerId(accountA.email, "msg-1")!!.copy(bodyLoaded = false))

        // The stub Worker only answers 304 for the ETag it handed out.
        detailResponse = null
        detailAlwaysNotModified = true

        val opened = repository.openThread(accountA, dao.getThreadByServerId(accountA.email, "msg-1")!!)

        assertEquals("cached body", opened.bodyText)
        val detailRequest = recorded.last { it.method == "GET" && it.path == "/api/mail/messages/msg-1" }
        assertEquals("\"etag-1\"", detailRequest.getHeader("If-None-Match"))
    }

    @Test
    fun `already loaded body skips the detail request entirely`() = runTest {
        repository.upsertAccount(accountA)
        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)
        dao.updateBody(accountA.email, "msg-1", "body", null, "\"e\"", "s")
        val thread = dao.getThreadByServerId(accountA.email, "msg-1")!!
        val before = recorded.count { it.path == "/api/mail/messages/msg-1" }

        repository.openThread(accountA, thread.copy(isRead = true))

        assertEquals(before, recorded.count { it.path == "/api/mail/messages/msg-1" })
    }

    @Test
    fun `starring a message updates Room optimistically and patches the server`() = runTest {
        repository.upsertAccount(accountA)
        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)
        val thread = dao.getThreadByServerId(accountA.email, "msg-1")!!

        repository.toggleStar(accountA, thread)

        assertTrue(dao.getThreadById(thread.id)!!.isStarred)
        val patch = recorded.last { it.method == "PATCH" && it.path == "/api/mail/messages/msg-1" }
        assertTrue(patch.body.readUtf8().contains("\"isStarred\":true"))
    }

    @Test
    fun `trashing a single message moves it locally and uses the single message endpoint`() = runTest {
        repository.upsertAccount(accountA)
        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)
        val thread = dao.getThreadByServerId(accountA.email, "msg-1")!!

        repository.moveToTrash(accountA, listOf(thread))

        assertEquals(MailFolder.TRASH, dao.getThreadById(thread.id)!!.folder)
        val patch = recorded.last { it.method == "PATCH" && it.path == "/api/mail/messages/msg-1" }
        assertTrue(patch.body.readUtf8().contains("\"moveToTrash\":true"))
    }

    @Test
    fun `trashing several messages uses the batch endpoint`() = runTest {
        repository.upsertAccount(accountA)
        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)
        val threads = dao.getThreadsForAccount(accountA.email)

        repository.moveToTrash(accountA, threads)

        assertTrue(dao.getThreadsForAccount(accountA.email).all { it.folder == MailFolder.TRASH })
        val batch = recorded.last { it.path == "/api/mail/messages/batch" }
        assertTrue(batch.body.readUtf8().contains("\"moveToTrash\":true"))
    }

    @Test
    fun `undo restores the previous folder and asks the server to move it back to inbox`() = runTest {
        repository.upsertAccount(accountA)
        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)
        val thread = dao.getThreadByServerId(accountA.email, "msg-1")!!
        repository.moveToTrash(accountA, listOf(thread))
        val trashed = dao.getThreadById(thread.id)!!

        repository.restoreFromTrash(accountA, listOf(trashed))

        assertEquals(MailFolder.PRIMARY, dao.getThreadById(thread.id)!!.folder)
        val restore = recorded.last { it.method == "PATCH" && it.path == "/api/mail/messages/msg-1" }
        assertTrue(restore.body.readUtf8().contains("\"moveToInbox\":true"))
    }

    @Test
    fun `permanent delete removes the cached row`() = runTest {
        repository.upsertAccount(accountA)
        repository.refreshCurrentFolder(accountA, MailFolder.TRASH)
        dao.insertThread(
            com.gothwad.mail.data.model.EmailThreadEntity(
                id = "a::msg-1",
                accountEmail = accountA.email,
                serverId = "msg-1",
                subject = "S",
                snippet = "",
                primarySenderName = "N",
                primarySenderEmail = "n@x.com",
                primarySenderColorHex = 0,
                folder = MailFolder.TRASH,
            ),
        )
        val thread = dao.getThreadByServerId(accountA.email, "msg-1")!!

        repository.deleteForever(accountA, listOf(thread))

        assertNull(dao.getThreadById(thread.id))
        assertTrue(recorded.any { it.method == "DELETE" && it.path == "/api/mail/messages/msg-1" })
    }

    @Test
    fun `empty trash calls the trash endpoint and clears the cached folder`() = runTest {
        repository.upsertAccount(accountA)
        dao.insertThread(
            com.gothwad.mail.data.model.EmailThreadEntity(
                id = "a::t1",
                accountEmail = accountA.email,
                serverId = "t1",
                subject = "S",
                snippet = "",
                primarySenderName = "N",
                primarySenderEmail = "n@x.com",
                primarySenderColorHex = 0,
                folder = MailFolder.TRASH,
            ),
        )

        val deleted = repository.emptyTrash(accountA)

        assertEquals(3, deleted)
        assertTrue(dao.getThreadsForAccount(accountA.email).none { it.folder == MailFolder.TRASH })
        assertTrue(recorded.any { it.method == "DELETE" && it.path == "/api/mail/trash" })
    }

    @Test
    fun `saving a draft posts to the drafts endpoint and caches the server id`() = runTest {
        repository.upsertAccount(accountA)

        val draft = repository.saveDraft(
            account = accountA,
            serverDraftId = null,
            to = listOf("b@c.com"),
            cc = emptyList(),
            subject = "Draft",
            body = "Draft body",
        )

        assertEquals("draft-1", draft.serverId)
        assertEquals(MailFolder.DRAFT, draft.folder)
        val request = recorded.last { it.path == "/api/mail/drafts" }
        assertEquals("POST", request.method)
        assertTrue(request.body.readUtf8().contains("\"subject\":\"Draft\""))
    }

    @Test
    fun `sending consumes the draft and reports the remaining quota`() = runTest {
        repository.upsertAccount(accountA)

        val result = repository.send(
            account = accountA,
            to = listOf("b@c.com"),
            cc = emptyList(),
            subject = "Hi",
            body = "Body",
            draftId = "draft-1",
        )

        assertTrue(result.sent)
        assertEquals(9, result.remainingToday)
        val send = recorded.last { it.path == "/api/mail/send" }
        assertTrue(send.body.readUtf8().contains("\"draftId\":\"draft-1\""))
        assertNull("The consumed draft must leave the cache", dao.getThreadByServerId(accountA.email, "draft-1"))
    }

    @Test
    fun `retention patch updates the account row`() = runTest {
        repository.upsertAccount(accountA)

        repository.updateRetention(accountA, com.gothwad.mail.data.model.RetentionPolicy.KEEP_1500)

        assertEquals(1500, dao.getAccount(accountA.email)!!.retentionLimit)
        val patch = recorded.last { it.path == "/api/mail/settings" && it.method == "PATCH" }
        assertTrue(patch.body.readUtf8().contains("\"retentionLimit\":1500"))
    }

    @Test
    fun `session and settings populate the storage meter and unread badge`() = runTest {
        repository.upsertAccount(accountA)

        val snapshot = repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)

        assertEquals(52_428_800L, snapshot.account.storageQuotaBytes)
        assertEquals(1_048_576L, snapshot.account.storageUsedBytes)
        assertEquals("1.0 MB", snapshot.account.usedStorageLabel)
        assertEquals("50 MB", snapshot.account.totalStorageLabel)
        // The stub Worker reports 4 stored messages, 1 of them starred.
        assertEquals(4, snapshot.account.totalMessages)
        assertEquals(1, snapshot.account.starredMessages)
        assertFalse("Nothing should fail while the stub answers every call", snapshot.messages.isEmpty())
        assertNotNull(snapshot.settings)
        assertEquals(3, snapshot.session?.unread?.get("pawan"))
    }

    @Test
    fun `server search results are cached without replacing the folder view`() = runTest {
        repository.upsertAccount(accountA)
        repository.refreshCurrentFolder(accountA, MailFolder.PRIMARY)
        val before = dao.getThreadsForAccount(accountA.email).size

        val results = repository.search(accountA, query = "Hello")

        assertTrue(results.isNotEmpty())
        assertTrue(dao.getThreadsForAccount(accountA.email).size >= before)
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        val inboxPageJson = """
            {"messages":[
              {"id":"msg-1","folder":"inbox","from":{"name":"Ananya","email":"ananya@gothwadtech.com"},
               "to":["pawan@gothwadtech.com"],"cc":[],"bcc":[],"subject":"Hello","snippet":"Hi there",
               "receivedAt":"2026-01-02T03:04:05.000Z","isRead":false,"isStarred":false,
               "attachments":[{"name":"spec.pdf","type":"application/pdf","size":2048}]},
              {"id":"msg-2","folder":"inbox","from":{"name":"Rohan","email":"rohan@gothwadtech.com"},
               "to":["pawan@gothwadtech.com"],"cc":[],"bcc":[],"subject":"Second","snippet":"Second snippet",
               "receivedAt":"2026-01-01T03:04:05.000Z","isRead":true,"isStarred":true,"attachments":[]}
            ],"folder":"inbox","nextBefore":"2026-01-01T03:04:05.000Z"}
        """.trimIndent()

        val settingsJson = """
            {"retentionLimit":500,"storageQuotaBytes":52428800,"storageUsedBytes":1048576,
             "totalMessages":4,"starredMessages":1,"updatedAt":"2026-01-02T03:04:05.000Z"}
        """.trimIndent()

        val sessionJson = """
            {"authenticated":true,"user":{"username":"pawan","email":"pawan@gothwadtech.com",
             "name":"Pawan Gothwad","firstName":"Pawan","lastName":"Gothwad","avatarUrl":null},
             "unread":{"pawan":3},"limits":{"dailySendLimit":20,"sendRemainingToday":9}}
        """.trimIndent()
    }
}
