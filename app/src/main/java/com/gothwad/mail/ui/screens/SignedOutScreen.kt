package com.gothwad.mail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gothwad.mail.auth.broker.DiscoveredAccount
import com.gothwad.mail.ui.components.SenderAvatar
import com.gothwad.mail.ui.state.AuthScreenState
import com.gothwad.mail.ui.state.MailUiState

/**
 * Clean Gothwad Mail welcome screen shown when no account holds a Mail grant.
 *
 * It offers the primary Gothwad sign-in, the Accounts account chooser, and one-tap sign-in for any
 * accounts discovered on this device through the verified peer directory.
 */
@Composable
fun SignedOutScreen(
    uiState: MailUiState,
    onSignIn: () -> Unit,
    onUseAnotherAccount: () -> Unit,
    onContinueAsDiscovered: (DiscoveredAccount) -> Unit,
    onOpenAccountManager: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("signed_out_screen"),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mail,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = "Gothwad Mail",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Welcome to Gothwad Mail",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Sign in with your Gothwad Account to read and send email",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                if (uiState.authState == AuthScreenState.LOADING) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.height(20.dp))
                }

                val available = uiState.availableToAdd
                if (available.isNotEmpty()) {
                    Text(
                        text = "Available on this device",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                    available.forEach { discovered ->
                        DiscoveredAccountCard(
                            discovered = discovered,
                            enabled = uiState.pendingSignInUsername == null,
                            onClick = { onContinueAsDiscovered(discovered) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                Button(
                    onClick = onSignIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("sign_in_with_gothwad_button"),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text("Sign in with Gothwad", style = MaterialTheme.typography.titleSmall)
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onUseAnotherAccount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("use_another_account_button"),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text("Use another account", style = MaterialTheme.typography.titleSmall)
                }

                if (uiState.authFailure != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = when (uiState.authFailure) {
                            com.gothwad.mail.auth.MailAuthFailure.StorageUnavailable ->
                                "Encrypted account storage is unavailable on this device."
                            com.gothwad.mail.auth.MailAuthFailure.InvalidCallback ->
                                "The sign-in response was invalid. Please try again."
                            com.gothwad.mail.auth.MailAuthFailure.SessionExpired ->
                                "Your Gothwad Account session expired. Sign in again."
                            else -> "Could not complete the sign-in. Please try again."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                TextButton(onClick = onOpenAccountManager) {
                    Text("Manage your Gothwad Account")
                }
            }
        }
    }
}

@Composable
private fun DiscoveredAccountCard(
    discovered: DiscoveredAccount,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("discovered_account_${discovered.username ?: discovered.userId}"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SenderAvatar(
                name = discovered.displayName,
                colorHex = com.gothwad.mail.data.repository.MailMappers.avatarColor(discovered.mailboxAddress),
                size = 40.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Continue as ${discovered.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = discovered.mailboxAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PersonAddAlt1,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
