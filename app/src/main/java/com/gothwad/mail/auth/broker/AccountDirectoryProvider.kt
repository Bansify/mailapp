package com.gothwad.mail.auth.broker

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Exposes the non-secret Gothwad account directory to mutually certificate-verified sibling apps.
 *
 * Security properties:
 * - `Binder.getCallingUid()` is captured before any dispatch or storage access.
 * - Every package sharing the caller UID must pass [TrustVerifier], so a shared UID cannot slip an
 *   untrusted app in beside a trusted one.
 * - Only identity metadata and tombstones are returned; access and refresh tokens never cross IPC.
 */
class AccountDirectoryProvider : ContentProvider() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val callingUid = Binder.getCallingUid()
        val ctx = context ?: throw SecurityException("Provider context unavailable")

        val host = directoryHost ?: throw SecurityException("Gothwad peer broker is not configured")
        val policy = host.brokerPolicy as? BrokerPolicy.PeerDiscovery
            ?: throw SecurityException("Gothwad peer broker is disabled")

        val verified = TrustVerifier.verifyBinderCaller(
            callingUid = callingUid,
            ownUid = Process.myUid(),
            ownPackage = ctx.packageName,
            packagesForUid = ctx.packageManager.getPackagesForUid(callingUid)?.toList(),
            policy = policy.trustPolicy,
            inspector = TrustVerifier.androidInspector(ctx),
            selfDebuggable = TrustVerifier.isSelfDebuggable(ctx),
        ) ?: throw SecurityException("Untrusted caller UID: $callingUid")

        if (arg != null && arg.toByteArray(Charsets.UTF_8).size > BrokerIpcContract.MAX_ARG_BYTES) {
            throw IllegalArgumentException("IPC argument exceeds bounds")
        }

        return when (method) {
            BrokerIpcContract.METHOD_LIST_ACCOUNTS -> {
                val snapshot = host.directorySnapshot()
                val encoded = json.encodeToString(snapshot)
                if (encoded.toByteArray(Charsets.UTF_8).size > BrokerIpcContract.MAX_SNAPSHOT_BYTES) {
                    throw IllegalStateException("Directory snapshot exceeds bounds")
                }
                Bundle().apply { putString(BrokerIpcContract.KEY_SNAPSHOT_JSON, encoded) }
            }

            BrokerIpcContract.METHOD_NOTIFY_ACCOUNT_REMOVED -> {
                val userId = extras?.getString(BrokerIpcContract.KEY_USER_ID)?.trim().orEmpty()
                val removedAt = extras?.getLong(BrokerIpcContract.KEY_REMOVED_AT, -1L) ?: -1L
                if (userId.isBlank() ||
                    userId.length > BrokerIpcContract.MAX_USER_ID_LENGTH ||
                    removedAt <= 0L
                ) {
                    throw IllegalArgumentException(
                        "Invalid removal payload from ${verified.packages.firstOrNull()}",
                    )
                }
                host.applyPeerDeviceRemoval(userId, removedAt)
                Bundle().apply { putBoolean(BrokerIpcContract.KEY_OK, true) }
            }

            else -> throw IllegalArgumentException("Unsupported directory method: $method")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        enforceTrustedCaller()
        return null
    }

    override fun getType(uri: Uri): String? {
        enforceTrustedCaller()
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceTrustedCaller()
        throw UnsupportedOperationException("Insert is not supported by the Gothwad directory")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceTrustedCaller()
        throw UnsupportedOperationException("Delete is not supported by the Gothwad directory")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        enforceTrustedCaller()
        throw UnsupportedOperationException("Update is not supported by the Gothwad directory")
    }

    private fun enforceTrustedCaller() {
        val callingUid = Binder.getCallingUid()
        val ctx = context ?: throw SecurityException("Provider context unavailable")
        val host = directoryHost ?: throw SecurityException("Gothwad peer broker is not configured")
        val policy = host.brokerPolicy as? BrokerPolicy.PeerDiscovery
            ?: throw SecurityException("Gothwad peer broker is disabled")
        TrustVerifier.verifyBinderCaller(
            callingUid = callingUid,
            ownUid = Process.myUid(),
            ownPackage = ctx.packageName,
            packagesForUid = ctx.packageManager.getPackagesForUid(callingUid)?.toList(),
            policy = policy.trustPolicy,
            inspector = TrustVerifier.androidInspector(ctx),
            selfDebuggable = TrustVerifier.isSelfDebuggable(ctx),
        ) ?: throw SecurityException("Untrusted caller UID: $callingUid")
    }

    companion object {
        /**
         * Late-bound host so the provider has no compile-time dependency on the application class
         * and stays unit testable. Registered by `MailApplication.onCreate`.
         */
        @Volatile
        var directoryHost: DirectoryHost? = null
    }
}

/** Directory operations the provider needs; implemented by `GothwadMailAuth`. */
interface DirectoryHost {
    val brokerPolicy: BrokerPolicy
    fun directorySnapshot(): DirectorySnapshot
    fun applyPeerDeviceRemoval(userId: String, removedAt: Long)
}
