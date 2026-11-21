package com.bansify.mail

import android.app.Application
import com.bansify.mail.auth.AuthConfig
import com.bansify.mail.auth.GothwadMailAuth
import com.bansify.mail.data.local.MailDatabase
import com.bansify.mail.data.remote.MailApiClient
import com.bansify.mail.data.repository.MailRepository

/**
 * Application entry point. Wires the encrypted Bansify auth session, the Mail Worker client and
 * the per-account Room cache together.
 */
class MailApplication : Application() {

    val database: MailDatabase by lazy { MailDatabase.getInstance(this) }

    val auth: GothwadMailAuth by lazy {
        GothwadMailAuth(application = this, config = AuthConfig.DEFAULT)
    }

    val apiClient: MailApiClient by lazy {
        MailApiClient(tokenProvider = auth)
    }

    val repository: MailRepository by lazy {
        MailRepository(database.mailDao(), apiClient)
    }

    override fun onCreate() {
        super.onCreate()
        auth.initialize()
    }
}
