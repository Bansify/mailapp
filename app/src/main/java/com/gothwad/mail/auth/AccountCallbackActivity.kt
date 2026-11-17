package com.gothwad.mail.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gothwad.mail.MainActivity
import com.gothwad.mail.MailApplication
import kotlinx.coroutines.launch

/**
 * Receives the Gothwad Accounts OAuth redirect for both registered redirect URIs:
 * the `com.gothwad.mail:/oauth/callback` custom scheme and the verified
 * `https://mail.gothwadtech.com/oauth/callback` App Link.
 *
 * The activity renders no UI. It forwards the raw callback to the application, which validates it
 * against the persisted PKCE state, and then hands control back to the main screen so the sign-in
 * result is visible immediately.
 */
class AccountCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val callback = intent?.data?.toString()
        val app = application as? MailApplication
        if (callback.isNullOrBlank() || app == null) {
            finish()
            return
        }
        lifecycleScope.launch { app.auth.handleCallback(callback) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
