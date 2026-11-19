package com.gothwad.mail.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gothwad.mail.MailApplication
import com.gothwad.mail.MainActivity

/**
 * Receives the Bansify Accounts OAuth redirect for both registered redirect URIs:
 * the `com.gothwad.mail:/oauth/callback` custom scheme and the verified
 * `https://mail.bansify.com/oauth/callback` App Link.
 *
 * The activity renders no UI. It hands the raw callback to the application, which validates it
 * against the persisted PKCE state, and immediately returns the user to the main screen.
 *
 * The callback is deliberately **not** run in `lifecycleScope`: this activity finishes itself as
 * soon as it has forwarded the user to [MainActivity], and a `lifecycleScope` coroutine is
 * cancelled in `onDestroy`. Doing the token exchange there aborted every sign-in right after the
 * browser returned, which looked like "sign-in succeeded but the app shows the welcome screen
 * again". The application owns the work instead, so it survives this activity finishing.
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
        // Application-scoped: completes even though this activity finishes right away.
        app.auth.acceptCallback(callback)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
