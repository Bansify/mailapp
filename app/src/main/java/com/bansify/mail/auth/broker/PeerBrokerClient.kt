package com.bansify.mail.auth.broker

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

    /**
     * Asks a signed-in peer app to issue a one-time PKCE code for this app (`issue_peer_code`).
     * Returns `null` when the peer is unreachable, untrusted or refuses.
     */
    fun issuePeerCode(
        packageName: String,
        request: PeerCodeRequest,
        policy: BrokerTrustPolicy,
    ): PeerAuthCodeResult? = null
}

/** Parameters of an `issue_peer_code` IPC call. Contains no secrets. */
data class PeerCodeRequest(
    val userId: String? = null,
    val username: String? = null,
    val email: String? = null,
    val targetClientId: String,
    val redirectUri: String,
    val scope: String,
    val codeChallenge: String,
    val codeChallengeMethod: String = "S256",
)

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

    override fun issuePeerCode(
        packageName: String,
        request: PeerCodeRequest,
        policy: BrokerTrustPolicy,
    ): PeerAuthCodeResult? {
        if (packageName == context.packageName) return null
        if (request.targetClientId.isBlank() ||
            request.codeChallenge.isBlank() ||
            request.redirectUri.isBlank() ||
            request.scope.isBlank()
        ) {
            return null
        }
        if (resolvePeerPackage(packageName) == null || !isTrusted(packageName, policy)) return null
        val authority = KnownGothwadApps.directoryAuthorityFor(packageName)
        return runCatching {
            val uri = Uri.parse("content://$authority")
            val extras = Bundle().apply {
                request.userId?.let { putString(BrokerIpcContract.KEY_USER_ID, it) }
                request.username?.let { putString(BrokerIpcContract.KEY_USERNAME, it) }
                request.email?.let { putString(BrokerIpcContract.KEY_EMAIL, it) }
                putString(BrokerIpcContract.KEY_TARGET_CLIENT_ID, request.targetClientId)
                putString(BrokerIpcContract.KEY_REDIRECT_URI, request.redirectUri)
                putString(BrokerIpcContract.KEY_SCOPE, request.scope)
                putString(BrokerIpcContract.KEY_CODE_CHALLENGE, request.codeChallenge)
                putString(BrokerIpcContract.KEY_CODE_CHALLENGE_METHOD, request.codeChallengeMethod)
            }
            val result = context.contentResolver.call(uri, BrokerIpcContract.METHOD_ISSUE_PEER_CODE, null, extras)
                ?: return@runCatching PeerAuthCodeResult.failure("no_response")
            if (!result.getBoolean(BrokerIpcContract.KEY_OK, false)) {
                return@runCatching PeerAuthCodeResult.failure(
                    result.getString(BrokerIpcContract.KEY_ERROR) ?: "peer_refused",
                )
            }
            val code = result.getString(BrokerIpcContract.KEY_CODE)?.takeIf { it.isNotBlank() }
                ?: return@runCatching PeerAuthCodeResult.failure("empty_code")
            PeerAuthCodeResult.success(
                PeerAuthCode(
                    code = code,
                    redirectUri = result.getString(BrokerIpcContract.KEY_REDIRECT_URI)
                        ?.takeIf { it.isNotBlank() } ?: request.redirectUri,
                    scope = result.getString(BrokerIpcContract.KEY_SCOPE),
                ),
            )
        }.getOrDefault(PeerAuthCodeResult.failure("peer_unreachable"))
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
        /** True when `com.bansify.accounts` is installed and launchable. */
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

    /**
     * Zero-browser silent SSO: asks the peer app that owns [discovered] for a one-time PKCE code.
     *
     * The peer app must already hold a Bansify Accounts session for that account; it exchanges its
     * own grant server-side and only a single-use code crosses IPC.
     */
    fun requestPeerAuthCode(
        discovered: DiscoveredAccount,
        targetClientId: String,
        redirectUri: String,
        scope: String,
        codeChallenge: String,
        policy: BrokerPolicy,
    ): PeerAuthCodeResult {
        val discovery = policy as? BrokerPolicy.PeerDiscovery
            ?: return PeerAuthCodeResult.failure("peer_discovery_disabled")
        if (discovered.sourcePackage == localPackage) {
            return PeerAuthCodeResult.failure("local_account")
        }
        val request = PeerCodeRequest(
            userId = discovered.userId.takeIf { it.isNotBlank() },
            username = discovered.username,
            email = discovered.email,
            targetClientId = targetClientId,
            redirectUri = redirectUri,
            scope = scope,
            codeChallenge = codeChallenge,
        )
        val result = transport.issuePeerCode(discovered.sourcePackage, request, discovery.trustPolicy)
        return result ?: PeerAuthCodeResult.failure("peer_code_unsupported")
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
        session: com.bansify.mail.auth.MailAuthSession,
        userId: String,
        removedAt: Long,
    ): com.bansify.mail.auth.MailAuthSession {
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
