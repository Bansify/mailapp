package com.gothwad.mail.auth.broker

import kotlinx.serialization.Serializable

/** First-party Gothwad applications that take part in peer account discovery. */
object KnownGothwadApps {
    const val ACCOUNTS = "com.gothwad.accounts"
    const val MAIL = "com.gothwad.mail"
    const val DRIVE = "com.gothwad.drive"
    const val STORE = "com.gothwad.store"
    const val TUBE = "com.gothwad.tube"
    const val SAMPLE_ALPHA = "com.gothwad.accounts.sample.alpha"
    const val SAMPLE_BETA = "com.gothwad.accounts.sample.beta"

    val FIRST_PARTY_PACKAGES: Set<String> = setOf(
        ACCOUNTS,
        MAIL,
        DRIVE,
        STORE,
        TUBE,
        SAMPLE_ALPHA,
        SAMPLE_BETA,
    )

    /** The production release certificate of the Gothwad first-party suite (lowercase hex). */
    const val RELEASE_CERT_SHA256: String =
        "504e2fc78ab68e018b848fc102762e4499a08803412e66a40b2b09403c6149b6"

    /** `<package>.gothwad.account.directory`, the authority every sibling app publishes. */
    fun directoryAuthorityFor(packageName: String): String = "$packageName.gothwad.account.directory"
}

/**
 * Wire contract of the directory ContentProvider. Values mirror
 * `com.gothwad.accounts.core.broker.BrokerIpcContract` exactly so both sides interoperate.
 */
object BrokerIpcContract {
    const val PROTOCOL_VERSION: Int = 1

    const val METHOD_LIST_ACCOUNTS: String = "list_accounts"
    const val METHOD_NOTIFY_ACCOUNT_REMOVED: String = "notify_account_removed"

    const val KEY_SNAPSHOT_JSON: String = "snapshot_json"
    const val KEY_USER_ID: String = "user_id"
    const val KEY_REMOVED_AT: String = "removed_at"
    const val KEY_OK: String = "ok"

    const val MAX_ARG_BYTES: Int = 4096
    const val MAX_SNAPSHOT_BYTES: Int = 256 * 1024
    const val MAX_USER_ID_LENGTH: Int = 256
}

/**
 * One non-secret identity record shared with sibling Gothwad apps.
 * Access and refresh tokens are intentionally absent from this type.
 */
@Serializable
data class DirectoryAccountRecord(
    val userId: String,
    val username: String? = null,
    val name: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val addedAt: Long = 0L,
    val lastSeenAt: Long = 0L,
)

/** Complete non-secret directory payload returned by `list_accounts`. */
@Serializable
data class DirectorySnapshot(
    val version: Int = BrokerIpcContract.PROTOCOL_VERSION,
    val sourcePackage: String = "",
    val accounts: List<DirectoryAccountRecord> = emptyList(),
    val tombstones: Map<String, Long> = emptyMap(),
)

/**
 * Directory record merged across this app and every verified peer app.
 * [isAuthorizedLocally] marks accounts that already hold a Mail OAuth grant.
 */
data class DiscoveredAccount(
    val userId: String,
    val username: String? = null,
    val name: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val sourcePackage: String = "",
    val addedAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val isAuthorizedLocally: Boolean = false,
) {
    /** Address shown on the one-tap "Continue as" card. */
    val mailboxAddress: String
        get() = email?.takeIf { it.isNotBlank() }
            ?: username?.takeIf { it.isNotBlank() }?.let { "$it@gothwadtech.com" }
            ?: userId

    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: username?.takeIf { it.isNotBlank() } ?: mailboxAddress
}

/** Caller identity resolved from a Binder UID. */
data class VerifiedCaller(val uid: Int, val packages: List<String>)

/** Trust policy implemented by [TrustVerifier]. */
sealed interface BrokerTrustPolicy {
    /**
     * Trust allowlisted first-party packages whose APK signing certificate matches this app's
     * certificate, optionally accepting the documented production release certificate.
     */
    data class SameSigningCertificate(
        val knownPackages: Set<String> = KnownGothwadApps.FIRST_PARTY_PACKAGES,
        val allowDebugBuild: Boolean = false,
        val pinnedReleaseCerts: Set<String> = setOf(KnownGothwadApps.RELEASE_CERT_SHA256),
    ) : BrokerTrustPolicy
}

/** Whether peer discovery is enabled at all. */
sealed interface BrokerPolicy {
    data object Disabled : BrokerPolicy
    data class PeerDiscovery(
        val trustPolicy: BrokerTrustPolicy = BrokerTrustPolicy.SameSigningCertificate(),
    ) : BrokerPolicy
}
