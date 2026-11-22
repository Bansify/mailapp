package com.bansify.mail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bansify.mail.ui.navigation.MailNavGraph
import com.bansify.mail.ui.theme.MailAppTheme
import com.bansify.mail.ui.viewmodel.MailViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MailViewModel by viewModels {
        val app = application as MailApplication
        MailViewModel.Factory(app.repository, app.auth, app.auth)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            MailAppTheme(themePreference = uiState.settings.themePreference) {
                // A Surface keeps a real background painted while the IME resizes the window;
                // without it the area behind the keyboard animates through black.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MailNavGraph(
                        viewModel = viewModel,
                        uiState = uiState
                    )
                }
            }
        }
    }
}
