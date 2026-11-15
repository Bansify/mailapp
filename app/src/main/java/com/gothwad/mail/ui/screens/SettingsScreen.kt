package com.gothwad.mail.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.DensitySmall
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gothwad.mail.data.model.AppSettingsEntity
import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.data.model.SwipeAction
import com.gothwad.mail.data.model.ThemePreference
import com.gothwad.mail.ui.components.SenderAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettingsEntity,
    activeAccount: MailAccountEntity?,
    onBack: () -> Unit,
    onUpdateSettings: (AppSettingsEntity) -> Unit,
    onUpdateAccount: (MailAccountEntity) -> Unit
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showSwipeDialog by remember { mutableStateOf<Boolean?>(null) } // true=right, false=left
    var showSignatureDialog by remember { mutableStateOf(false) }
    var showVacationDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "General settings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )

            SettingsRow(
                icon = Icons.Rounded.Brightness6,
                title = "Theme",
                subtitle = settings.themePreference.label,
                onClick = { showThemeDialog = true }
            )

            SettingsRow(
                icon = Icons.Rounded.Swipe,
                title = "Right swipe action",
                subtitle = settings.rightSwipeAction.label,
                onClick = { showSwipeDialog = true }
            )

            SettingsRow(
                icon = Icons.Rounded.Swipe,
                title = "Left swipe action",
                subtitle = settings.leftSwipeAction.label,
                onClick = { showSwipeDialog = false }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onUpdateSettings(settings.copy(compactDensity = !settings.compactDensity))
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.DensitySmall,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Compact conversation list",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Hide attachment previews in inbox rows",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.compactDensity,
                    onCheckedChange = {
                        onUpdateSettings(settings.copy(compactDensity = it))
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (activeAccount != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SenderAvatar(
                        name = activeAccount.displayName,
                        colorHex = activeAccount.avatarColorHex,
                        size = 32.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = activeAccount.email,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                SettingsRow(
                    icon = Icons.Rounded.EditNote,
                    title = "Mobile signature",
                    subtitle = activeAccount.signature.replace("\n", " "),
                    onClick = { showSignatureDialog = true }
                )

                SettingsRow(
                    icon = Icons.Rounded.FlightTakeoff,
                    title = "Vacation responder",
                    subtitle = if (activeAccount.vacationEnabled) "On · Auto-reply active" else "Off",
                    onClick = { showVacationDialog = true }
                )
            }
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Choose theme") },
            text = {
                Column {
                    ThemePreference.entries.forEach { pref ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateSettings(settings.copy(themePreference = pref))
                                    showThemeDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.themePreference == pref,
                                onClick = {
                                    onUpdateSettings(settings.copy(themePreference = pref))
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(pref.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Cancel") }
            }
        )
    }

    showSwipeDialog?.let { isRight ->
        val current = if (isRight) settings.rightSwipeAction else settings.leftSwipeAction
        AlertDialog(
            onDismissRequest = { showSwipeDialog = null },
            title = { Text(if (isRight) "Right swipe action" else "Left swipe action") },
            text = {
                Column {
                    SwipeAction.entries.forEach { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val updated = if (isRight) {
                                        settings.copy(rightSwipeAction = action)
                                    } else {
                                        settings.copy(leftSwipeAction = action)
                                    }
                                    onUpdateSettings(updated)
                                    showSwipeDialog = null
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = current == action,
                                onClick = {
                                    val updated = if (isRight) {
                                        settings.copy(rightSwipeAction = action)
                                    } else {
                                        settings.copy(leftSwipeAction = action)
                                    }
                                    onUpdateSettings(updated)
                                    showSwipeDialog = null
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(action.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSwipeDialog = null }) { Text("Cancel") }
            }
        )
    }

    if (showSignatureDialog && activeAccount != null) {
        var sigText by remember(activeAccount) { mutableStateOf(activeAccount.signature) }
        AlertDialog(
            onDismissRequest = { showSignatureDialog = false },
            title = { Text("Mobile signature") },
            text = {
                OutlinedTextField(
                    value = sigText,
                    onValueChange = { sigText = it },
                    label = { Text("Signature") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateAccount(activeAccount.copy(signature = sigText))
                    showSignatureDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSignatureDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showVacationDialog && activeAccount != null) {
        var enabled by remember(activeAccount) { mutableStateOf(activeAccount.vacationEnabled) }
        var message by remember(activeAccount) { mutableStateOf(activeAccount.vacationMessage) }
        AlertDialog(
            onDismissRequest = { showVacationDialog = false },
            title = { Text("Vacation responder") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable auto-reply", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text("Message") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateAccount(
                        activeAccount.copy(vacationEnabled = enabled, vacationMessage = message)
                    )
                    showVacationDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showVacationDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
