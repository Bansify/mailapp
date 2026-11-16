package com.gothwad.mail.auth

import com.gothwad.mail.auth.broker.BrokerPolicy
import com.gothwad.mail.auth.broker.BrokerTrustPolicy
import com.gothwad.mail.auth.broker.DirectoryAccountRecord
import com.gothwad.mail.auth.broker.DirectorySnapshot
import com.gothwad.mail.auth.broker.KnownGothwadApps
import com.gothwad.mail.auth.broker.PeerBrokerClient
import com.gothwad.mail.auth.broker.PeerDirectoryTransport
import com.gothwad.mail.auth.broker.SignatureInspector
import com.gothwad.mail.auth.broker.TrustVerifier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic signer table so trust decisions can be verified without installed APKs. */
private class FakeSignatureInspector(
    private val signers: Map<String, Set<String>>,
    private val debuggable: Set<String> = emptySet(),
) : SignatureInspector {
    override fun signerSha256(packageName: String): Set<String> = signers[packageName].orEmpty()
    override fun isDebuggable(packageName: String): Boolean = packageName in debuggable
}

private class FakeTransport(
    private val peers: Map<String, DirectorySnapshot>,
) : PeerDirectoryTransport {
    val notified = mutableListOf<Triple<String, String, Long>>()

    override fun listVerifiedPeers(policy: BrokerTrustPolicy): List<String> = peers.keys.sorted()

    override fun queryPeer(packageName: String, policy: BrokerTrustPolicy): DirectorySnapshot? = peers[packageName]

    override fun notifyAccountRemoved(
        packageName: String,
        userId: String,
        removedAt: Long,
        policy: BrokerTrustPolicy,
    ): Boolean {
        notified.add(Triple(packageName, userId, removedAt))
        return true
    }
}

class PeerBrokerDiscoveryTest {

    private val releaseCert = KnownGothwadApps.RELEASE_CERT_SHA256
    private val ownPackage = KnownGothwadApps.MAIL

    private fun policy(allowDebug: Boolean = false) = BrokerTrustPolicy.SameSigningCertificate(
        knownPackages = KnownGothwadApps.FIRST_PARTY_PACKAGES,
        allowDebugBuild = allowDebug,
    )

    @Test
    fun `same signing certificate peer is trusted`() {
        val inspector = FakeSignatureInspector(
            mapOf(
                ownPackage to setOf(releaseCert),
                KnownGothwadApps.ACCOUNTS to setOf(releaseCert),
            ),
        )
        assertTrue(
            TrustVerifier.verifyPackage(
                packageName = KnownGothwadApps.ACCOUNTS,
                ownPackage = ownPackage,
                policy = policy(),
                inspector = inspector,
                selfDebuggable = false,
            ),
        )
    }

    @Test
    fun `peer with a different certificate is rejected`() {
        val inspector = FakeSignatureInspector(
            mapOf(
                ownPackage to setOf(releaseCert),
                KnownGothwadApps.ACCOUNTS to setOf("f".repeat(64)),
            ),
        )
        assertFalse(
            TrustVerifier.verifyPackage(
                packageName = KnownGothwadApps.ACCOUNTS,
                ownPackage = ownPackage,
                policy = policy(),
                inspector = inspector,
                selfDebuggable = false,
            ),
        )
    }

    @Test
    fun `unknown package is rejected even when the certificate matches`() {
        val inspector = FakeSignatureInspector(
            mapOf(
                ownPackage to setOf(releaseCert),
                "com.evil.app" to setOf(releaseCert),
            ),
        )
        assertFalse(
            TrustVerifier.verifyPackage(
                packageName = "com.evil.app",
                ownPackage = ownPackage,
                policy = policy(),
                inspector = inspector,
                selfDebuggable = false,
            ),
        )
    }

    @Test
    fun `debug peers are rejected unless debug builds are allowed`() {
        val inspector = FakeSignatureInspector(
            signers = mapOf(
                ownPackage to setOf(releaseCert),
                KnownGothwadApps.DRIVE to setOf(releaseCert),
            ),
            debuggable = setOf(KnownGothwadApps.DRIVE),
        )
        assertFalse(
            TrustVerifier.verifyPackage(
                KnownGothwadApps.DRIVE, ownPackage, policy(), inspector, selfDebuggable = false,
            ),
        )
        assertTrue(
            TrustVerifier.verifyPackage(
                KnownGothwadApps.DRIVE, ownPackage, policy(allowDebug = true), inspector, selfDebuggable = true,
            ),
        )
    }

    @Test
    fun `binder caller with a shared uid is rejected when any package is untrusted`() {
        val inspector = FakeSignatureInspector(
            mapOf(
                ownPackage to setOf(releaseCert),
                KnownGothwadApps.ACCOUNTS to setOf(releaseCert),
                KnownGothwadApps.STORE to setOf("a".repeat(64)),
            ),
        )
        val mixed = TrustVerifier.verifyBinderCaller(
            callingUid = 10_123,
            ownUid = 10_000,
            ownPackage = ownPackage,
            packagesForUid = listOf(KnownGothwadApps.ACCOUNTS, KnownGothwadApps.STORE),
            policy = policy(),
            inspector = inspector,
            selfDebuggable = false,
        )
        assertNull(mixed)

        val verified = TrustVerifier.verifyBinderCaller(
            callingUid = 10_123,
            ownUid = 10_000,
            ownPackage = ownPackage,
            packagesForUid = listOf(KnownGothwadApps.ACCOUNTS),
            policy = policy(),
            inspector = inspector,
            selfDebuggable = false,
        )
        assertNotNull(verified)
        assertEquals(listOf(KnownGothwadApps.ACCOUNTS), verified?.packages)
    }

    @Test
    fun `the calling process itself is always verified`() {
        val inspector = FakeSignatureInspector(emptyMap())
        val caller = TrustVerifier.verifyBinderCaller(
            callingUid = 10_000,
            ownUid = 10_000,
            ownPackage = ownPackage,
            packagesForUid = null,
            policy = policy(),
            inspector = inspector,
            selfDebuggable = true,
        )
        assertNotNull(caller)
    }

    @Test
    fun `accounts from a verified sibling app are discovered`() {
        val peerSnapshot = DirectorySnapshot(
            sourcePackage = KnownGothwadApps.ACCOUNTS,
            accounts = listOf(
                DirectoryAccountRecord(
                    userId = "user-accounts",
                    username = "ananya",
                    name = "Ananya Verma",
                    email = "ananya@gothwadtech.com",
                    addedAt = 1_000L,
                    lastSeenAt = 2_000L,
                ),
            ),
        )
        val client = PeerBrokerClient(
            localPackage = ownPackage,
            transport = FakeTransport(mapOf(KnownGothwadApps.ACCOUNTS to peerSnapshot)),
        )

        val discovered = client.discoverAccounts(
            localSnapshot = DirectorySnapshot(sourcePackage = ownPackage),
            localUserIds = emptySet(),
            policy = BrokerPolicy.PeerDiscovery(policy()),
        )

        assertEquals(1, discovered.size)
        val account = discovered.first()
        assertEquals("ananya", account.username)
        assertEquals(KnownGothwadApps.ACCOUNTS, account.sourcePackage)
        assertFalse(account.isAuthorizedLocally)
        assertEquals("ananya@gothwadtech.com", account.mailboxAddress)
    }

    @Test
    fun `locally authorized accounts sort first and are marked authorized`() {
        val local = DirectorySnapshot(
            sourcePackage = ownPackage,
            accounts = listOf(
                DirectoryAccountRecord(userId = "mine", username = "pawan", lastSeenAt = 10L),
            ),
        )
        val peer = DirectorySnapshot(
            sourcePackage = KnownGothwadApps.DRIVE,
            accounts = listOf(
                DirectoryAccountRecord(userId = "mine", username = "pawan", lastSeenAt = 5L),
                DirectoryAccountRecord(userId = "other", username = "rohan", lastSeenAt = 99L),
            ),
        )
        val client = PeerBrokerClient(
            localPackage = ownPackage,
            transport = FakeTransport(mapOf(KnownGothwadApps.DRIVE to peer)),
        )

        val discovered = client.discoverAccounts(local, setOf("mine"), BrokerPolicy.PeerDiscovery(policy()))

        assertEquals(2, discovered.size)
        assertTrue(discovered.first().isAuthorizedLocally)
        assertEquals("mine", discovered.first().userId)
    }

    @Test
    fun `device removal tombstones hide accounts from every peer`() {
        val peer = DirectorySnapshot(
            sourcePackage = KnownGothwadApps.ACCOUNTS,
            accounts = listOf(
                DirectoryAccountRecord(userId = "removed", username = "gone", lastSeenAt = 100L),
                DirectoryAccountRecord(userId = "kept", username = "kept", lastSeenAt = 100L),
            ),
            tombstones = mapOf("removed" to 500L),
        )
        val client = PeerBrokerClient(
            localPackage = ownPackage,
            transport = FakeTransport(mapOf(KnownGothwadApps.ACCOUNTS to peer)),
        )

        val discovered = client.discoverAccounts(
            DirectorySnapshot(sourcePackage = ownPackage),
            emptySet(),
            BrokerPolicy.PeerDiscovery(policy()),
        )

        assertEquals(1, discovered.size)
        assertEquals("kept", discovered.first().username)
    }

    @Test
    fun `device removal is broadcast to every verified peer`() {
        val transport = FakeTransport(
            mapOf(
                KnownGothwadApps.ACCOUNTS to DirectorySnapshot(sourcePackage = KnownGothwadApps.ACCOUNTS),
                KnownGothwadApps.DRIVE to DirectorySnapshot(sourcePackage = KnownGothwadApps.DRIVE),
            ),
        )
        val client = PeerBrokerClient(ownPackage, transport)

        val notified = client.broadcastDeviceRemoval("user-1", 123L, BrokerPolicy.PeerDiscovery(policy()))

        assertEquals(listOf(KnownGothwadApps.ACCOUNTS, KnownGothwadApps.DRIVE), notified)
        assertEquals(2, transport.notified.size)
        assertTrue(transport.notified.all { it.second == "user-1" && it.third == 123L })
    }

    @Test
    fun `disabled broker policy performs no peer IPC`() {
        val transport = FakeTransport(
            mapOf(KnownGothwadApps.ACCOUNTS to DirectorySnapshot(sourcePackage = KnownGothwadApps.ACCOUNTS)),
        )
        val client = PeerBrokerClient(ownPackage, transport)

        assertTrue(client.installedVerifiedPeers(BrokerPolicy.Disabled).isEmpty())
        assertTrue(client.broadcastDeviceRemoval("user-1", 1L, BrokerPolicy.Disabled).isEmpty())
        assertTrue(transport.notified.isEmpty())
    }

    @Test
    fun `directory snapshot never leaks tokens`() {
        val snapshot = DirectorySnapshot(
            sourcePackage = ownPackage,
            accounts = listOf(
                DirectoryAccountRecord(
                    userId = "user-1",
                    username = "pawan",
                    name = "Pawan Gothwad",
                    email = "pawan@gothwadtech.com",
                    addedAt = 1L,
                    lastSeenAt = 2L,
                ),
            ),
        )
        val encoded = Json { encodeDefaults = true }.encodeToString(snapshot)

        assertFalse(encoded.contains("access_token", ignoreCase = true))
        assertFalse(encoded.contains("refresh_token", ignoreCase = true))
        assertTrue(encoded.contains("\"username\":\"pawan\""))
        assertTrue("Protocol version must be advertised: $encoded", encoded.contains("\"version\":1"))
    }

    @Test
    fun `peer ledger is applied through the shared session model`() {
        val session = MailAuthSession(
            credentials = listOf(
                MailCredential(
                    account = MailAuthAccount(
                        userId = "user-1",
                        username = "pawan",
                        email = "pawan@gothwadtech.com",
                        displayName = "Pawan",
                        addedAt = 10L,
                        lastSeenAt = 10L,
                    ),
                    accessToken = "a",
                    refreshToken = "r",
                    expiresAt = 0L,
                ),
            ),
            activeUserId = "user-1",
        )
        val client = PeerBrokerClient(
            ownPackage,
            FakeTransport(emptyMap()),
        )

        val applied = client.applyPeerDeviceRemoval(session, "user-1", 100L)
        assertTrue(applied.credentials.isEmpty())
        assertEquals(100L, applied.deviceRemovalTombstones["user-1"])

        val ignored = client.applyPeerDeviceRemoval(session, "user-1", 5L)
        assertTrue("An older tombstone must not remove a newer credential", ignored.credentials.isNotEmpty())
    }
}
