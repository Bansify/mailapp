package com.gothwad.mail.auth.broker

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import kotlinx.serialization.json.Json

/** Transport boundary for peer directory IPC, swappable in unit tests. */
interface PeerDirectoryTransport {
    /** Installed, verified first-party packages that publish a directory provider. */
    fun listVerifiedPeers(policy: BrokerTrustPolicy): List<String>

    /** Reads the peer's directory snapshot, or `null` when the peer is unreachable/untrusted. */
    fun queryPeer(packageName: String, policy: BrokerTrustPolicy): DirectorySnapshot?

    /** Sends a device-removal notification; returns whether the peer acknowledged it. */
    fun notifyAccountRemoved(packageName: String, userId: String, removedAt: Long, policy: BrokerTrustPolicy): Boolean
}

/**
 * ContentResolver-backed transport.
 *
 * The peer is re-verified immediately before every IPC call, and both the provider authority and
 * the resolved package name must match the expected sibling app.
 */
class AndroidPeerDirectoryTransport(private val context: Context) : PeerDirectoryTransport {

    private val json = Json { ignoreUnknownKeys = true }
    private val inspector = TrustVerifier.androidInspector(context)
    private val selfDebuggable = TrustVerifier.isSelfDebuggable(context)

    override fun listVerifiedPeers(policy: BrokerTrustPolicy): List<String> {
        val candidates = when (policy) {
            is BrokerTrustPolicy.SameSigningCertificate -> policy.knownPackages
        }
        return candidates
            .filter { it != context.packageName }
            .filter { pkg -> resolvePeerPackage(pkg) != null && isTrusted(pkg, policy) }
            .sorted()
    }

    override fun queryPeer(packageName: String, policy: BrokerTrustPolicy): DirectorySnapshot? {
        if (packageName == context.packageName) return null
        if (resolvePeerPackage(packageName) == null || !isTrusted(packageName, policy)) return null
        val authority = KnownGothwadApps.directoryAuthorityFor(packageName)
        return runCatching {
            val uri = Uri.parse("content://$authority")
            val response = context.contentResolver.call(uri, BrokerIpcContract.METHOD_LIST_ACCOUNTS, null, null)
                ?: return@runCatching null
            val raw = response.getString(BrokerIpcContract.KEY_SNAPSHOT_JSON) ?: return@runCatching null
            if (raw.toByteArray().size > BrokerIpcContract.MAX_SNAPSHOT_BYTES) return@runCatching null
            json.decodeFromString<DirectorySnapshot>(raw)
        }.getOrNull()
    }

    override fun notifyAccountRemoved(
        packageName: String,
        userId: String,
        removedAt: Long,
        policy: BrokerTrustPolicy,
    ): Boolean {
        if (packageName == context.packageName) return false
        if (userId.isBlank() || userId.length > BrokerIpcContract.MAX_USER_ID_LENGTH || removedAt <= 0L) return false
        if (resolvePeerPackage(packageName) == null || !isTrusted(packageName, policy)) return false
        val authority = KnownGothwadApps.directoryAuthorityFor(packageName)
        return runCatching {
            val uri = Uri.parse("content://$authority")
            val extras = Bundle().apply {
                putString(BrokerIpcContract.KEY_USER_ID, userId)
                putLong(BrokerIpcContract.KEY_REMOVED_AT, removedAt)
            }
            val result = context.contentResolver.call(uri, BrokerIpcContract.METHOD_NOTIFY_ACCOUNT_REMOVED, null, extras)
            result?.getBoolean(BrokerIpcContract.KEY_OK, false) == true
        }.getOrDefault(false)
    }

    private fun resolvePeerPackage(packageName: String): String? {
        val authority = KnownGothwadApps.directoryAuthorityFor(packageName)
        val info = context.packageManager.resolveContentProvider(authority, 0) ?: return null
        return info.packageName?.takeIf { it == packageName }
    }

    private fun isTrusted(packageName: String, policy: BrokerTrustPolicy): Boolean =
        TrustVerifier.verifyPackage(
            packageName = packageName,
            ownPackage = context.packageName,
            policy = policy,
            inspector = inspector,
            selfDebuggable = selfDebuggable,
        )

    companion object {
        /** True when `com.gothwad.accounts` is installed and launchable. */
        fun isAccountsAppInstalled(context: Context): Boolean = runCatching {
            context.packageManager.getLaunchIntentForPackage(KnownGothwadApps.ACCOUNTS) != null ||
                context.packageManager.getPackageInfo(KnownGothwadApps.ACCOUNTS, 0) != null
        }.getOrDefault(false)

        private fun PackageManager.getPackageInfoSafe(packageName: String) =
            runCatching { getPackageInfo(packageName, 0) }.getOrNull()
    }
}

/**
 * Client half of mutual certificate-verified peer account discovery.
 *
 * Merges this app's own directory with every verified sibling app, applies the newest
 * device-removal tombstones, and never exposes OAuth tokens over IPC.
 */
class PeerBrokerClient(
    private val localPackage: String,
    private val transport: PeerDirectoryTransport,
) {
    /** Packages that can be offered on the signed-out screen and the account switcher. */
    fun installedVerifiedPeers(policy: BrokerPolicy): List<String> = when (policy) {
        BrokerPolicy.Disabled -> emptyList()
        is BrokerPolicy.PeerDiscovery -> transport.listVerifiedPeers(policy.trustPolicy)
    }

    /** Merges the local snapshot with all verified peers, newest record wins. */
    fun discoverAccounts(
        localSnapshot: DirectorySnapshot,
        localUserIds: Set<String>,
        policy: BrokerPolicy,
    ): List<DiscoveredAccount> {
        if (policy is BrokerPolicy.Disabled) {
            return localSnapshot.accounts.map { it.toDiscovered(localPackage, localUserIds) }
        }
        val discovery = policy as BrokerPolicy.PeerDiscovery
        val snapshots = mutableListOf(localSnapshot)
        transport.listVerifiedPeers(discovery.trustPolicy).forEach { pkg ->
            transport.queryPeer(pkg, discovery.trustPolicy)?.let { snapshots.add(it) }
        }

        val tombstones = mutableMapOf<String, Long>()
        snapshots.forEach { snapshot ->
            snapshot.tombstones.forEach { (userId, removedAt) ->
                tombstones[userId] = maxOf(tombstones[userId] ?: 0L, removedAt)
            }
        }

        val merged = linkedMapOf<String, DiscoveredAccount>()
        snapshots.forEach { snapshot ->
            val source = snapshot.sourcePackage.ifBlank { localPackage }
            snapshot.accounts.forEach { record ->
                val isLocal = record.userId in localUserIds
                val removedAt = tombstones[record.userId] ?: 0L
                val freshness = maxOf(record.addedAt, record.lastSeenAt)
                // A removed account stays hidden on this device until it is signed in again.
                if (!isLocal && removedAt > 0L && freshness <= removedAt) return@forEach
                val candidate = record.toDiscovered(source, localUserIds)
                val existing = merged[record.userId]
                if (existing == null ||
                    (candidate.isAuthorizedLocally && !existing.isAuthorizedLocally) ||
                    candidate.lastSeenAt > existing.lastSeenAt
                ) {
                    merged[record.userId] = candidate.copy(
                        isAuthorizedLocally = existing?.isAuthorizedLocally == true || candidate.isAuthorizedLocally,
                    )
                } else if (isLocal && !existing.isAuthorizedLocally) {
                    merged[record.userId] = existing.copy(isAuthorizedLocally = true)
                }
            }
        }

        return merged.values.sortedWith(
            compareByDescending<DiscoveredAccount> { it.isAuthorizedLocally }
                .thenByDescending { it.lastSeenAt }
                .thenBy { it.email ?: it.userId },
        )
    }

    /** Notifies every verified sibling app that an account was removed from this device. */
    fun broadcastDeviceRemoval(userId: String, removedAt: Long, policy: BrokerPolicy): List<String> {
        val discovery = policy as? BrokerPolicy.PeerDiscovery ?: return emptyList()
        return transport.listVerifiedPeers(discovery.trustPolicy).filter { pkg ->
            transport.notifyAccountRemoved(pkg, userId, removedAt, discovery.trustPolicy)
        }
    }

    /**
     * Applies a tombstone received from a verified peer. Local credentials are only dropped when
     * they are not newer than the removal timestamp, so a re-sign-in is never clobbered.
     */
    fun applyPeerDeviceRemoval(
        session: com.gothwad.mail.auth.MailAuthSession,
        userId: String,
        removedAt: Long,
    ): com.gothwad.mail.auth.MailAuthSession {
        if (userId.isBlank() || removedAt <= 0L) return session
        val existing = session.deviceRemovalTombstones[userId] ?: 0L
        val effective = maxOf(existing, removedAt)
        val credential = session.credentialFor(userId)
        val shouldRemove = credential != null &&
            maxOf(credential.account.addedAt, credential.account.lastSeenAt) <= effective
        val remaining = if (shouldRemove) session.credentials.filterNot { it.account.userId == userId } else session.credentials
        val nextActive = if (shouldRemove && session.activeUserId == userId) {
            remaining.firstOrNull()?.account?.userId
        } else {
            session.activeUserId
        }
        return session.copy(
            credentials = remaining,
            activeUserId = nextActive,
            deviceRemovalTombstones = session.deviceRemovalTombstones + (userId to effective),
        )
    }

    private fun DirectoryAccountRecord.toDiscovered(source: String, localUserIds: Set<String>) = DiscoveredAccount(
        userId = userId,
        username = username,
        name = name,
        email = email,
        avatarUrl = avatarUrl,
        sourcePackage = source,
        addedAt = addedAt,
        lastSeenAt = lastSeenAt,
        isAuthorizedLocally = userId in localUserIds,
    )
}
