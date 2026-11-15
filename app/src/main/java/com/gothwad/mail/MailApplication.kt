package com.gothwad.mail

import android.app.Application
import com.gothwad.mail.data.local.MailDatabase
import com.gothwad.mail.data.repository.MailRepository

class MailApplication : Application() {
    val database: MailDatabase by lazy { MailDatabase.getInstance(this) }
    val repository: MailRepository by lazy { MailRepository(database.mailDao()) }
}
