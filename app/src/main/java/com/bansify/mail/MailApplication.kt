package com.bansify.mail

import android.app.Application
import android.util.Log
import com.bansify.accounts.core.BrokerPolicy
import com.bansify.accounts.core.BrokerTrustPolicy
import com.bansify.accounts.core.GothwadAccounts
import com.bansify.accounts.core.GothwadConfig
import com.bansify.accounts.core.Scope
import com.bansify.mail.auth.MailAuth
import com.bansify.mail.data.local.MailDatabase
import com.bansify.mail.data.remote.MailApiClient
import com.bansify.mail.data.repository.MailRepository

/**
 * Application entry point. Wires the encrypted Bansify auth session, the Mail Worker client and
 * the per-account Room cache together.
 */
class MailApplication : Application() {

    val database: MailDatabase by lazy { MailDatabase.getInstance(this) }

    val auth: MailAuth by lazy { MailAuth() }

    val apiClient: MailApiClient by lazy {
        MailApiClient(tokenProvider = auth)
    }

    val repository: MailRepository by lazy {
        MailRepository(database.mailDao(), apiClient)
    }

    override fun onCreate() {
        super.onCreate()
        auth.initialize()
        initSharedAccountsSdk()
    }

    /**
     * Initializes the shared Bansify Accounts SDK (`com.github.Bansify.accountsapp`).
     *
     * This is the same client the standalone Accounts app and every sibling Bansify app use, so the
     * sign-in surface, the account centre and cross-app peer discovery stay identical everywhere.
     * The SDK owns its own encrypted credential store; the legacy [auth] stack is still initialized
     * until the UI migration in the follow-up change.
     */
    /** Set when the shared Accounts SDK could not be initialised; the UI shows it instead of crashing. */
    var startupError: String? = null
        private set

    private fun initSharedAccountsSdk() {
        runCatching {
            GothwadAccounts.init(
                this,
                GothwadConfig(
                    clientId = "gothwad-mail",
                    redirectUri = if (BuildConfig.DEBUG) {
                        "$packageName:/oauth/callback"
                    } else {
                        "https://mail.bansify.com/oauth/callback"
                    },
                    scopes = setOf(
                        Scope.Profile,
                        Scope.Email,
                        Scope.OfflineAccess,
                        Scope.Mail,
                        Scope.AccountRead,
                        Scope.AccountProfileWrite,
                    ),
                    brokerPolicy = BrokerPolicy.PeerDiscovery(
                        trustPolicy = BrokerTrustPolicy.SameSigningCertificate(
                            allowDebugBuild = BuildConfig.DEBUG,
                        ),
                    ),
                    allowCustomSchemeRedirect = BuildConfig.DEBUG,
                ),
            )
        }.onFailure { error ->
            startupError = error.stackTraceToString().lineSequence().take(4).joinToString("\n")
            Log.e("MailApplication", "Accounts SDK init failed", error)
        }
    }
}
