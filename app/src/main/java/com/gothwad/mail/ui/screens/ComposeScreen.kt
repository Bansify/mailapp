package com.gothwad.mail.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ScheduleSend
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.ui.state.ComposeInitialData
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    initialData: ComposeInitialData,
    activeAccount: MailAccountEntity?,
    allAccounts: List<MailAccountEntity>,
    onBackAndSaveDraft: (
        draftId: String?,
        fromAccount: MailAccountEntity,
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String,
        attachments: List<AttachmentItem>
    ) -> Unit,
    onDiscardAndExit: () -> Unit,
    onSend: (
        draftId: String?,
        replyToThreadId: String?,
        fromAccount: MailAccountEntity,
        to: List<String>,
        cc: List<String>,
        subject: String,
        body: String,
        attachments: List<AttachmentItem>,
        isConfidential: Boolean,
        scheduledForMillis: Long?
    ) -> Unit
) {
    val context = LocalContext.current
    var selectedFrom by remember(activeAccount) {
        mutableStateOf(activeAccount ?: allAccounts.firstOrNull())
    }
    var showFromMenu by remember { mutableStateOf(false) }

    val toRecipients = remember(initialData) {
        mutableStateListOf<String>().apply { addAll(initialData.initialTo) }
    }
    var toInput by remember { mutableStateOf("") }

    val ccRecipients = remember(initialData) {
        mutableStateListOf<String>().apply { addAll(initialData.initialCc) }
    }
    var ccInput by remember { mutableStateOf("") }
    var showCcBcc by remember { mutableStateOf(initialData.initialCc.isNotEmpty()) }

    var subject by remember(initialData) { mutableStateOf(initialData.initialSubject) }
    var body by remember(initialData) {
        val signatureSuffix = if (initialData.replyToThreadId == null && initialData.draftId == null &&
            activeAccount != null && activeAccount.signature.isNotBlank()
        ) {
            "\n\n--\n${activeAccount.signature}"
        } else {
            ""
        }
        mutableStateOf(initialData.initialBody + signatureSuffix)
    }

    val attachments = remember(initialData) {
        mutableStateListOf<AttachmentItem>().apply { addAll(initialData.initialAttachments) }
    }

    var isConfidential by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val suggestedContacts = remember {
        listOf(
            "ananya.verma@bansify.com",
            "rohan@bansify.com",
            "siddharth@bansify.com",
            "vikram.mehta@bansify.com",
            "team@bansify.com"
        )
    }

    // Play-Policy Zero-Permission Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            val name = resolveDisplayName(context, it, "Image_${attachments.size + 1}.jpg")
            attachments.add(
                AttachmentItem(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    sizeLabel = "Photo",
                    mimeType = "image/jpeg",
                    uriString = it.toString()
                )
            )
        }
    }

    // Play-Policy Storage Access Framework Document Picker
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val name = resolveDisplayName(context, it, "Document_${attachments.size + 1}.pdf")
            val mime = context.contentResolver.getType(it) ?: "application/octet-stream"
            attachments.add(
                AttachmentItem(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    sizeLabel = "File",
                    mimeType = mime,
                    uriString = it.toString()
                )
            )
        }
    }

    fun commitToInput() {
        val candidate = toInput.trim().trimEnd(',')
        if (candidate.contains("@")) {
            if (candidate !in toRecipients) toRecipients.add(candidate)
            toInput = ""
            validationError = null
        }
    }

    fun commitCcInput() {
        val candidate = ccInput.trim().trimEnd(',')
        if (candidate.contains("@")) {
            if (candidate !in ccRecipients) ccRecipients.add(candidate)
            ccInput = ""
        }
    }

    fun handleBack() {
        commitToInput()
        commitCcInput()
        val from = selectedFrom
        if (from != null) {
            onBackAndSaveDraft(
                initialData.draftId,
                from,
                toRecipients.toList(),
                ccRecipients.toList(),
                subject,
                body,
                attachments.toList()
            )
        } else {
            onDiscardAndExit()
        }
    }

    fun triggerSend(scheduledForMillis: Long? = null) {
        commitToInput()
        commitCcInput()
        val from = selectedFrom ?: return
        if (toRecipients.isEmpty()) {
            validationError = "Add at least one recipient"
            return
        }
        onSend(
            initialData.draftId,
            initialData.replyToThreadId,
            from,
            toRecipients.toList(),
            ccRecipients.toList(),
            subject,
            body,
            attachments.toList(),
            isConfidential,
            scheduledForMillis
        )
    }

    BackHandler(onBack = ::handleBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(
                        onClick = ::handleBack,
                        modifier = Modifier.testTag("compose_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Save draft and go back"
                        )
                    }
                },
                title = {
                    Text(
                        text = initialData.modeTitle,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showAttachMenu = true },
                            modifier = Modifier.testTag("compose_attach_button")
                        ) {
                            Icon(Icons.Rounded.AttachFile, contentDescription = "Attach file")
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Attach file") },
                                leadingIcon = { Icon(Icons.Rounded.AttachFile, contentDescription = null) },
                                onClick = {
                                    showAttachMenu = false
                                    documentPickerLauncher.launch(arrayOf("*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Insert photo") },
                                leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                                onClick = {
                                    showAttachMenu = false
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                        }
                    }

                    IconButton(
                        onClick = { triggerSend(null) },
                        modifier = Modifier.testTag("compose_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send email",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More compose options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Schedule send") },
                                leadingIcon = { Icon(Icons.Rounded.ScheduleSend, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    commitToInput()
                                    if (toRecipients.isEmpty()) {
                                        validationError = "Add at least one recipient"
                                    } else {
                                        showScheduleDialog = true
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (isConfidential) "Disable confidential mode" else "Confidential mode")
                                },
                                leadingIcon = { Icon(Icons.Rounded.LockClock, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    isConfidential = !isConfidential
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Discard") },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    onDiscardAndExit()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Formatting Helper Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
                modifier = Modifier.imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { body += "**bold** " }) {
                        Icon(Icons.Rounded.FormatBold, contentDescription = "Insert bold")
                    }
                    IconButton(onClick = { body += "_italic_ " }) {
                        Icon(Icons.Rounded.FormatItalic, contentDescription = "Insert italic")
                    }
                    IconButton(onClick = { body += "\n• " }) {
                        Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, contentDescription = "Insert bullet")
                    }
                    IconButton(onClick = { body += "\n> " }) {
                        Icon(Icons.Rounded.FormatQuote, contentDescription = "Insert quote")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // From Row
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFromMenu = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "From",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp)
                    )
                    Text(
                        text = selectedFrom?.email ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = "Choose sender account",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showFromMenu,
                    onDismissRequest = { showFromMenu = false }
                ) {
                    allAccounts.forEach { acc ->
                        DropdownMenuItem(
                            text = { Text("${acc.displayName} (${acc.email})") },
                            onClick = {
                                selectedFrom = acc
                                showFromMenu = false
                            }
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // To Row with Recipient Chips
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "To",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .width(56.dp)
                            .padding(top = 10.dp)
                    )
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        toRecipients.forEach { email ->
                            InputChip(
                                selected = false,
                                onClick = { toRecipients.remove(email) },
                                label = { Text(email, style = MaterialTheme.typography.bodySmall) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove $email",
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            )
                        }
                        BasicTextField(
                            value = toInput,
                            onValueChange = { next ->
                                if (next.endsWith(",") || next.endsWith(" ")) {
                                    toInput = next.dropLast(1)
                                    commitToInput()
                                } else {
                                    toInput = next
                                    validationError = null
                                }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { commitToInput() }),
                            decorationBox = { inner ->
                                Box(modifier = Modifier.padding(vertical = 8.dp)) {
                                    if (toInput.isEmpty() && toRecipients.isEmpty()) {
                                        Text(
                                            text = "Recipients",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("compose_to_input")
                        )
                    }

                    IconButton(onClick = { showCcBcc = !showCcBcc }) {
                        Icon(
                            imageVector = if (showCcBcc) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "Toggle Cc"
                        )
                    }
                }

                // Suggested contacts autocomplete pills when typing
                val matchingSuggestions = remember(toInput, toRecipients.size) {
                    if (toInput.isBlank()) {
                        suggestedContacts.filter { it !in toRecipients }.take(2)
                    } else {
                        suggestedContacts.filter {
                            it.contains(toInput.trim(), ignoreCase = true) && it !in toRecipients
                        }
                    }
                }
                if (matchingSuggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 56.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        matchingSuggestions.forEach { contact ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.clickable {
                                    toRecipients.add(contact)
                                    toInput = ""
                                    validationError = null
                                }
                            ) {
                                Text(
                                    text = "+ $contact",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                validationError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 56.dp, top = 4.dp)
                    )
                }
            }

            // Expandable Cc Field
            AnimatedVisibility(visible = showCcBcc) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cc",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(56.dp)
                        )
                        FlowRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ccRecipients.forEach { email ->
                                InputChip(
                                    selected = false,
                                    onClick = { ccRecipients.remove(email) },
                                    label = { Text(email, style = MaterialTheme.typography.bodySmall) },
                                    trailingIcon = {
                                        Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(15.dp))
                                    }
                                )
                            }
                            BasicTextField(
                                value = ccInput,
                                onValueChange = { next ->
                                    if (next.endsWith(",")) {
                                        ccInput = next.dropLast(1)
                                        commitCcInput()
                                    } else {
                                        ccInput = next
                                    }
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { commitCcInput() }),
                                decorationBox = { inner ->
                                    if (ccInput.isEmpty() && ccRecipients.isEmpty()) {
                                        Text(
                                            text = "Cc recipients",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Subject Row
            BasicTextField(
                value = subject,
                onValueChange = { subject = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        if (subject.isEmpty()) {
                            Text(
                                text = "Subject",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("compose_subject_input")
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (isConfidential) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LockClock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Confidential mode enabled · Forwarding & copying restricted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Attachments Chips
            if (attachments.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    attachments.forEach { att ->
                        InputChip(
                            selected = false,
                            onClick = { attachments.remove(att) },
                            label = { Text(att.name, style = MaterialTheme.typography.labelMedium) },
                            trailingIcon = {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove attachment", modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }

            // Body Editor
            BasicTextField(
                value = body,
                onValueChange = { body = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        if (body.isEmpty()) {
                            Text(
                                text = "Compose email",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .testTag("compose_body_input")
            )
        }
    }

    if (showScheduleDialog) {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val options = listOf(
            "In 2 hours" to (now + 2 * hour),
            "Tomorrow morning · 8:00 AM" to (now + 12 * hour),
            "Monday morning · 8:00 AM" to (now + 36 * hour)
        )
        AlertDialog(
            onDismissRequest = { showScheduleDialog = false },
            title = { Text("Schedule send") },
            text = {
                Column {
                    options.forEach { (label, targetTime) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showScheduleDialog = false
                                    triggerSend(targetTime)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ScheduleSend,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScheduleDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun resolveDisplayName(context: android.content.Context, uri: Uri, fallback: String): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else fallback
            } else fallback
        } ?: fallback
    }.getOrDefault(fallback)
}
