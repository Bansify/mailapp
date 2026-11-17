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
        ) ?: return denied(BrokerIpcContract.ERROR_UNTRUSTED_CALLER)

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

            BrokerIpcContract.METHOD_ISSUE_PEER_CODE -> {
                val targetClientId = extras?.getString(BrokerIpcContract.KEY_TARGET_CLIENT_ID)?.trim().orEmpty()
                val redirectUri = extras?.getString(BrokerIpcContract.KEY_REDIRECT_URI)?.trim().orEmpty()
                val scope = extras?.getString(BrokerIpcContract.KEY_SCOPE)?.trim().orEmpty()
                val challenge = extras?.getString(BrokerIpcContract.KEY_CODE_CHALLENGE)?.trim().orEmpty()
                val method = extras?.getString(BrokerIpcContract.KEY_CODE_CHALLENGE_METHOD)
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: "S256"
                val userId = extras?.getString(BrokerIpcContract.KEY_USER_ID)?.trim()?.takeIf { it.isNotEmpty() }
                val username = extras?.getString(BrokerIpcContract.KEY_USERNAME)?.trim()?.takeIf { it.isNotEmpty() }
                val email = extras?.getString(BrokerIpcContract.KEY_EMAIL)?.trim()?.takeIf { it.isNotEmpty() }

                if (targetClientId.isEmpty() || redirectUri.isEmpty() || scope.isEmpty() ||
                    challenge.isEmpty() ||
                    listOf(targetClientId, redirectUri, scope, challenge, method).any {
                        it.length > BrokerIpcContract.MAX_FIELD_LENGTH
                    } ||
                    method != "S256"
                ) {
                    return denied(BrokerIpcContract.ERROR_INVALID_REQUEST)
                }

                val issued = host.issuePeerCode(
                    userId = userId,
                    username = username,
                    email = email,
                    targetClientId = targetClientId,
                    redirectUri = redirectUri,
                    scope = scope,
                    codeChallenge = challenge,
                    codeChallengeMethod = method,
                )
                if (issued == null) {
                    denied(BrokerIpcContract.ERROR_NOT_SIGNED_IN)
                } else {
                    Bundle().apply {
                        putBoolean(BrokerIpcContract.KEY_OK, true)
                        putString(BrokerIpcContract.KEY_CODE, issued.code)
                        putString(BrokerIpcContract.KEY_REDIRECT_URI, issued.redirectUri)
                        issued.scope?.let { putString(BrokerIpcContract.KEY_SCOPE, it) }
                    }
                }
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

    /** A refusal bundle: `{ ok: false, error: <code> }`, never an exception to the caller. */
    private fun denied(error: String): Bundle = Bundle().apply {
        putBoolean(BrokerIpcContract.KEY_OK, false)
        putString(BrokerIpcContract.KEY_ERROR, error)
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

    /**
     * Issues a one-time PKCE code for a trusted sibling app that is signing in silently.
     *
     * Returns `null` when no local credential matches the request (or refreshing it failed), which
     * the provider reports as `not_signed_in` so the caller can fall back to an interactive
     * sign-in. Implementations must never return tokens over IPC.
     */
    fun issuePeerCode(
        userId: String?,
        username: String?,
        email: String?,
        targetClientId: String,
        redirectUri: String,
        scope: String,
        codeChallenge: String,
        codeChallengeMethod: String,
    ): PeerAuthCode?
}
