package com.gothwad.mail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gothwad.mail.ui.navigation.MailNavGraph
import com.gothwad.mail.ui.theme.MailAppTheme
import com.gothwad.mail.ui.viewmodel.MailViewModel

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
                MailNavGraph(
                    viewModel = viewModel,
                    uiState = uiState
                )
            }
        }
    }
}
