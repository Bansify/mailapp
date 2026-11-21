package com.bansify.mail.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bansify.mail.R
import com.bansify.mail.data.model.AttachmentItem
import com.bansify.mail.data.model.EmailThreadEntity
import com.bansify.mail.data.model.SwipeAction
import com.bansify.mail.ui.components.AttachmentViewerDialog
import com.bansify.mail.ui.components.EmailThreadRow
import com.bansify.mail.ui.state.SearchFilterState

@Composable
fun SearchScreen(
    searchState: SearchFilterState,
    results: List<EmailThreadEntity>,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onCommitHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onToggleFilter: (unread: Boolean?, starred: Boolean?, hasAttachment: Boolean?, sentByMe: Boolean?) -> Unit,
    onOpenThread: (EmailThreadEntity) -> Unit,
    onToggleStar: (EmailThreadEntity) -> Unit,
    onSwipe: (EmailThreadEntity, SwipeAction) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var previewAttachment by remember { mutableStateOf<AttachmentItem?>(null) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val hasActiveQueryOrFilter = searchState.query.isNotBlank() ||
        searchState.onlyUnread ||
        searchState.onlyStarred ||
        searchState.onlyHasAttachment ||
        searchState.onlySentByMe

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("search_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }

                    BasicTextField(
                        value = searchState.query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { onCommitHistory(searchState.query) }
                        ),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (searchState.query.isEmpty()) {
                                    Text(
                                        text = "Search in mail",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .testTag("search_input_field")
                    )

                    if (searchState.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                }

                // Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = searchState.onlyUnread,
                        onClick = { onToggleFilter(!searchState.onlyUnread, null, null, null) },
                        label = { Text("Is unread") }
                    )
                    FilterChip(
                        selected = searchState.onlyHasAttachment,
                        onClick = { onToggleFilter(null, null, !searchState.onlyHasAttachment, null) },
                        label = { Text("Has attachment") }
                    )
                    FilterChip(
                        selected = searchState.onlyStarred,
                        onClick = { onToggleFilter(null, !searchState.onlyStarred, null, null) },
                        label = { Text("Starred") }
                    )
                    FilterChip(
                        selected = searchState.onlySentByMe,
                        onClick = { onToggleFilter(null, null, null, !searchState.onlySentByMe) },
                        label = { Text("Sent by me") }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasActiveQueryOrFilter) {
                if (searchState.recentSearches.isNotEmpty()) {
                    item(key = "recent_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent mail searches",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onClearHistory) {
                                Text("Clear")
                            }
                        }
                    }
                    items(searchState.recentSearches, key = { it }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onQueryChange(item) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            } else if (results.isEmpty()) {
                item(key = "no_results") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SearchOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.empty_search_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.empty_search_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                item(key = "results_count") {
                    Text(
                        text = "${results.size} result${if (results.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                items(results, key = { it.id }) { thread ->
                    EmailThreadRow(
                        thread = thread,
                        isSelected = false,
                        onClick = {
                            onCommitHistory(searchState.query)
                            onOpenThread(thread)
                        },
                        onLongClick = {},
                        onAvatarClick = { onOpenThread(thread) },
                        onToggleStar = { onToggleStar(thread) },
                        onAttachmentClick = { previewAttachment = it },
                        onSwipe = { action -> onSwipe(thread, action) }
                    )
                }
            }
        }
    }

    previewAttachment?.let { att ->
        AttachmentViewerDialog(
            attachment = att,
            onDismiss = { previewAttachment = null }
        )
    }
}
