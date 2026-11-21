package com.bansify.mail.auth.broker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Process
import java.security.MessageDigest

/** Package metadata lookup, separated so unit tests can supply synthetic signers. */
interface SignatureInspector {
    /** Lowercase SHA-256 hex fingerprints of the signing certificates of [packageName]. */
    fun signerSha256(packageName: String): Set<String>

    /** True when [packageName] is installed and debuggable. */
    fun isDebuggable(packageName: String): Boolean
}

/**
 * Mutual certificate verification for peer account discovery.
 *
 * Both directions are verified: the caller UID must resolve to packages that all pass the
 * policy, and outbound queries only target packages that pass the same policy. Debug builds are
 * rejected unless [BrokerTrustPolicy.SameSigningCertificate.allowDebugBuild] is set.
 */
object TrustVerifier {

    /**
     * Verifies the Binder caller. Returns `null` when the caller is untrusted, which callers must
     * translate into a [SecurityException].
     */
    fun verifyBinderCaller(
        callingUid: Int,
        ownUid: Int,
        ownPackage: String,
        packagesForUid: List<String>?,
        policy: BrokerTrustPolicy,
        inspector: SignatureInspector,
        selfDebuggable: Boolean,
    ): VerifiedCaller? {
        if (callingUid < 0) return null
        if (callingUid == ownUid) return VerifiedCaller(callingUid, listOf(ownPackage))
        val packages = packagesForUid ?: return null
        if (packages.isEmpty()) return null
        // Reject ambiguous shared UIDs unless every package sharing the UID is trusted.
        if (!packages.all { verifyPackage(it, ownPackage, policy, inspector, selfDebuggable) }) return null
        return VerifiedCaller(callingUid, packages.toList())
    }

    /** Verifies one package: allowlisted, non-debug, and signed with an accepted certificate. */
    fun verifyPackage(
        packageName: String,
        ownPackage: String,
        policy: BrokerTrustPolicy,
        inspector: SignatureInspector,
        selfDebuggable: Boolean,
    ): Boolean {
        if (packageName.isBlank()) return false
        when (policy) {
            is BrokerTrustPolicy.SameSigningCertificate -> {
                if (packageName !in policy.knownPackages) return false
                if (selfDebuggable && !policy.allowDebugBuild) return false
                if (inspector.isDebuggable(packageName) && !policy.allowDebugBuild) return false
                val peerSigners = inspector.signerSha256(packageName)
                if (peerSigners.isEmpty()) return false
                val accepted = acceptedCertificates(ownPackage, policy, inspector)
                if (accepted.isEmpty()) return false
                return peerSigners.all { it.lowercase() in accepted }
            }
        }
    }

    /** Certificates this app is willing to accept: its own signers plus pinned release certs. */
    fun acceptedCertificates(
        ownPackage: String,
        policy: BrokerTrustPolicy.SameSigningCertificate,
        inspector: SignatureInspector,
    ): Set<String> {
        val own = inspector.signerSha256(ownPackage).map { it.lowercase() }.toSet()
        return own + policy.pinnedReleaseCerts.map { it.lowercase() }
    }

    /** Builds the Android-backed inspector for [context]. */
    fun androidInspector(context: Context): SignatureInspector = object : SignatureInspector {
        private val packageManager: PackageManager = context.packageManager

        override fun signerSha256(packageName: String): Set<String> =
            extractCurrentSignersSha256(packageManager, packageName)

        override fun isDebuggable(packageName: String): Boolean {
            if (packageName == context.packageName) {
                return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            }
            val flags = runCatching { packageManager.getApplicationInfo(packageName, 0).flags }.getOrNull()
                ?: return false
            return (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }
    }

    /** True when the running app itself is debuggable. */
    fun isSelfDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Extracts the current signing certificate fingerprints of [packageName]. */
    fun extractCurrentSignersSha256(packageManager: PackageManager, packageName: String): Set<String> =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return@runCatching emptySet()
                val signatures: Array<Signature> = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory?.lastOrNull()?.let { arrayOf(it) }
                        ?: signingInfo.apkContentsSigners
                }
                signatures.map(::sha256Hex).toSet()
            } else {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val signatures = info.signatures ?: emptyArray()
                signatures.map(::sha256Hex).toSet()
            }
        }.getOrDefault(emptySet())

    /** UID of the current process. */
    fun ownUid(): Int = Process.myUid()

    private fun sha256Hex(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
