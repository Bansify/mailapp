package com.gothwad.mail.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gothwad.mail.R
import com.gothwad.mail.auth.broker.DiscoveredAccount
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.ui.components.AccountSwitcherDialog
import com.gothwad.mail.ui.components.AttachmentViewerDialog
import com.gothwad.mail.ui.components.CategoryTeaserRow
import com.gothwad.mail.ui.components.EmailThreadRow
import com.gothwad.mail.ui.components.MailNavigationDrawerSheet
import com.gothwad.mail.ui.components.SenderAvatar
import com.gothwad.mail.ui.state.BottomNavTab
import com.gothwad.mail.ui.state.MailUiState
import com.gothwad.mail.ui.state.QuickFilterChip
import com.gothwad.mail.ui.viewmodel.MailSnackbarEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    uiState: MailUiState,
    snackbarEvents: SharedFlow<MailSnackbarEvent>,
    onSelectFolder: (MailFolder) -> Unit,
    onSelectLabel: (String) -> Unit,
    onSelectBottomTab: (BottomNavTab) -> Unit,
    onToggleQuickFilter: (QuickFilterChip) -> Unit,
    onRefresh: () -> Unit,
    onOpenThread: (EmailThreadEntity) -> Unit,
    onSelectTabletThread: (String?) -> Unit,
    onEditDraft: (EmailThreadEntity) -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onArchiveSelected: () -> Unit,
    onTrashSelected: () -> Unit,
    onMarkSelectedRead: (Boolean) -> Unit,
    onToggleStar: (EmailThreadEntity) -> Unit,
    onSwipeThread: (EmailThreadEntity) -> Unit,
    onSwipeLeftThread: (EmailThreadEntity) -> Unit,
    onArchiveThread: (EmailThreadEntity) -> Unit,
    onTrashThread: (EmailThreadEntity) -> Unit,
    onRestoreThread: (EmailThreadEntity) -> Unit,
    onMarkThreadUnread: (EmailThreadEntity) -> Unit,
    onToggleImportant: (EmailThreadEntity) -> Unit,
    onReportSpam: (EmailThreadEntity) -> Unit,
    onSnoozeThread: (EmailThreadEntity, String) -> Unit,
    onUpdateLabels: (EmailThreadEntity, List<String>) -> Unit,
    onReply: (EmailThreadEntity) -> Unit,
    onReplyAll: (EmailThreadEntity) -> Unit,
    onForward: (EmailThreadEntity) -> Unit,
    onUndoMove: (List<EmailThreadEntity>) -> Unit,
    onEmptyCurrentFolder: () -> Unit,
    onOpenCompose: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onManageAccount: () -> Unit,
    onContinueAsDiscovered: (DiscoveredAccount) -> Unit,
    onCreateMeeting: () -> String,
    onJoinMeeting: (String) -> Unit,
    onLeaveMeeting: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val isFabExpanded by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 60 }
    }

    var showAccountDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var previewAttachment by remember { mutableStateOf<AttachmentItem?>(null) }

    LaunchedEffect(snackbarEvents) {
        snackbarEvents.collect { event ->
            when (event) {
                is MailSnackbarEvent.MessageWithUndo -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        onUndoMove(event.affectedThreads)
                    }
                }
                is MailSnackbarEvent.SimpleMessage -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MailNavigationDrawerSheet(
                selectedFolder = uiState.selectedFolder,
                selectedLabelFilter = uiState.selectedLabelFilter,
                unreadCounts = uiState.folderUnreadCounts,
                draftCount = uiState.draftCount,
                availableLabels = uiState.availableLabels,
                onSelectFolder = { folder ->
                    onSelectFolder(folder)
                    scope.launch { drawerState.close() }
                },
                onSelectLabel = { label ->
                    onSelectLabel(label)
                    scope.launch { drawerState.close() }
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                onOpenHelp = {
                    scope.launch { drawerState.close() }
                    showHelpDialog = true
                }
            )
        }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 840.dp
            val primaryUnread = uiState.folderUnreadCounts[MailFolder.PRIMARY] ?: 0

            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                floatingActionButton = {
                    if (uiState.activeBottomTab == BottomNavTab.MAIL && !uiState.isSelectionMode) {
                        ExtendedFloatingActionButton(
                            onClick = onOpenCompose,
                            expanded = isFabExpanded,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            icon = {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = stringResource(R.string.compose)
                                )
                            },
                            text = {
                                Text(
                                    text = stringResource(R.string.compose),
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            modifier = Modifier.testTag("compose_fab")
                        )
                    }
                },
                bottomBar = {
                    if (!isWideScreen && !uiState.isSelectionMode) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            NavigationBarItem(
                                selected = uiState.activeBottomTab == BottomNavTab.MAIL,
                                onClick = { onSelectBottomTab(BottomNavTab.MAIL) },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (primaryUnread > 0) {
                                                Badge { Text(primaryUnread.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (uiState.activeBottomTab == BottomNavTab.MAIL) {
                                                Icons.Rounded.Mail
                                            } else {
                                                Icons.Rounded.MailOutline
                                            },
                                            contentDescription = stringResource(R.string.nav_mail)
                                        )
                                    }
                                },
                                label = { Text(stringResource(R.string.nav_mail)) },
                                modifier = Modifier.testTag("bottom_tab_mail")
                            )
                            NavigationBarItem(
                                selected = uiState.activeBottomTab == BottomNavTab.MEET,
                                onClick = { onSelectBottomTab(BottomNavTab.MEET) },
                                icon = {
                                    Icon(
                                        imageVector = if (uiState.activeBottomTab == BottomNavTab.MEET) {
                                            Icons.Rounded.Videocam
                                        } else {
                                            Icons.Rounded.VideocamOff
                                        },
                                        contentDescription = stringResource(R.string.nav_meet)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_meet)) },
                                modifier = Modifier.testTag("bottom_tab_meet")
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isWideScreen) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Rounded.Menu, contentDescription = "Open drawer")
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            NavigationRailItem(
                                selected = uiState.activeBottomTab == BottomNavTab.MAIL,
                                onClick = { onSelectBottomTab(BottomNavTab.MAIL) },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (primaryUnread > 0) {
                                                Badge { Text(primaryUnread.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Mail, contentDescription = "Mail")
                                    }
                                },
                                label = { Text("Mail") }
                            )
                            NavigationRailItem(
                                selected = uiState.activeBottomTab == BottomNavTab.MEET,
                                onClick = { onSelectBottomTab(BottomNavTab.MEET) },
                                icon = { Icon(Icons.Rounded.Videocam, contentDescription = "Meet") },
                                label = { Text("Meet") }
                            )
                        }
                    }

                    if (uiState.activeBottomTab == BottomNavTab.MEET) {
                        MeetTabContent(
                            activeAccount = uiState.activeAccount,
                            meetings = uiState.meetings,
                            activeMeetingCode = uiState.activeMeetingCode,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onOpenAccountDialog = { showAccountDialog = true },
                            onCreateMeeting = onCreateMeeting,
                            onJoinMeeting = onJoinMeeting,
                            onLeaveMeeting = onLeaveMeeting,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val listModifier = if (isWideScreen) {
                            Modifier
                                .weight(0.45f)
                                .fillMaxHeight()
                        } else {
                            Modifier.fillMaxSize()
                        }

                        Column(modifier = listModifier) {
                            // Top Bar: Either Contextual Selection Mode OR Pill Search Bar
                            AnimatedContent(
                                targetState = uiState.isSelectionMode,
                                label = "inbox_top_bar"
                            ) { selectionMode ->
                                if (selectionMode) {
                                    val anyUnreadSelected = uiState.visibleThreads
                                        .filter { it.id in uiState.selectedThreadIds }
                                        .any { !it.isRead }

                                    TopAppBar(
                                        colors = TopAppBarDefaults.topAppBarColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ),
                                        navigationIcon = {
                                            IconButton(onClick = onClearSelection) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                                    contentDescription = "Clear selection"
                                                )
                                            }
                                        },
                                        title = {
                                            Text(
                                                text = "${uiState.selectedThreadIds.size}",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        },
                                        actions = {
                                            IconButton(onClick = onArchiveSelected) {
                                                Icon(Icons.Rounded.Archive, contentDescription = "Archive selected")
                                            }
                                            IconButton(onClick = onTrashSelected) {
                                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete selected")
                                            }
                                            IconButton(onClick = { onMarkSelectedRead(anyUnreadSelected) }) {
                                                Icon(
                                                    imageVector = if (anyUnreadSelected) {
                                                        Icons.Rounded.MarkEmailRead
                                                    } else {
                                                        Icons.Rounded.MarkEmailUnread
                                                    },
                                                    contentDescription = "Toggle read state"
                                                )
                                            }
                                            IconButton(onClick = onSelectAll) {
                                                Icon(Icons.Rounded.SelectAll, contentDescription = "Select all")
                                            }
                                        }
                                    )
                                } else {
                                    PillSearchTopBar(
                                        activeAccountName = uiState.activeAccount?.displayName ?: "Pawan Bansify",
                                        activeAccountColor = uiState.activeAccount?.avatarColorHex ?: 0xFF0B57D0,
                                        onOpenDrawer = { scope.launch { drawerState.open() } },
                                        onOpenSearch = onOpenSearch,
                                        onOpenAccountSwitcher = { showAccountDialog = true },
                                        modifier = if (isWideScreen) Modifier else Modifier.statusBarsPadding()
                                    )
                                }
                            }

                            PullToRefreshBox(
                                isRefreshing = uiState.isRefreshing,
                                onRefresh = onRefresh,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 96.dp)
                                ) {
                                    // Current Folder Label + Quick Filters
                                    item(key = "folder_header") {
                                        val title = uiState.selectedLabelFilter?.let { "Label: $it" }
                                            ?: uiState.selectedFolder.displayName

                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = title.uppercase(),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                            )

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState())
                                                    .padding(horizontal = 16.dp, vertical = 2.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                QuickFilterChip.entries.forEach { chip ->
                                                    val selected = chip in uiState.activeQuickFilters
                                                    FilterChip(
                                                        selected = selected,
                                                        onClick = { onToggleQuickFilter(chip) },
                                                        label = { Text(chip.label) },
                                                        leadingIcon = if (selected) {
                                                            {
                                                                Icon(
                                                                    imageVector = Icons.Rounded.Done,
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                        } else null
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Empty Trash / Spam Banner
                                    if ((uiState.selectedFolder == MailFolder.TRASH || uiState.selectedFolder == MailFolder.SPAM) &&
                                        uiState.visibleThreads.isNotEmpty()
                                    ) {
                                        item(key = "empty_banner") {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "Messages in ${uiState.selectedFolder.displayName} for more than 30 days will be deleted.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    TextButton(onClick = onEmptyCurrentFolder) {
                                                        Text("Empty now")
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Gmail-style Promotions / Social / Updates Category Teasers inside Primary
                                    if (uiState.selectedFolder == MailFolder.PRIMARY &&
                                        uiState.selectedLabelFilter == null &&
                                        uiState.activeQuickFilters.isEmpty()
                                    ) {
                                        val promoUnread = uiState.folderUnreadCounts[MailFolder.PROMOTIONS] ?: 0
                                        val socialUnread = uiState.folderUnreadCounts[MailFolder.SOCIAL] ?: 0
                                        val updatesUnread = uiState.folderUnreadCounts[MailFolder.UPDATES] ?: 0

                                        if (promoUnread > 0) {
                                            item(key = "teaser_promotions") {
                                                CategoryTeaserRow(
                                                    folder = MailFolder.PROMOTIONS,
                                                    unreadCount = promoUnread,
                                                    previewSenders = "KeyCraft Studio, JetBrains Digest",
                                                    onClick = { onSelectFolder(MailFolder.PROMOTIONS) }
                                                )
                                            }
                                        }
                                        if (socialUnread > 0) {
                                            item(key = "teaser_social") {
                                                CategoryTeaserRow(
                                                    folder = MailFolder.SOCIAL,
                                                    unreadCount = socialUnread,
                                                    previewSenders = "GitHub Community",
                                                    onClick = { onSelectFolder(MailFolder.SOCIAL) }
                                                )
                                            }
                                        }
                                        if (updatesUnread > 0) {
                                            item(key = "teaser_updates") {
                                                CategoryTeaserRow(
                                                    folder = MailFolder.UPDATES,
                                                    unreadCount = updatesUnread,
                                                    previewSenders = "Account Security",
                                                    onClick = { onSelectFolder(MailFolder.UPDATES) }
                                                )
                                            }
                                        }
                                    }

                                    if (uiState.visibleThreads.isEmpty() && !uiState.isLoading) {
                                        item(key = "empty_state") {
                                            EmptyFolderState(
                                                folderName = uiState.selectedLabelFilter ?: uiState.selectedFolder.displayName
                                            )
                                        }
                                    } else {
                                        items(
                                            items = uiState.visibleThreads,
                                            key = { it.id }
                                        ) { thread ->
                                            EmailThreadRow(
                                                thread = thread,
                                                isSelected = thread.id in uiState.selectedThreadIds,
                                                isTabletActive = isWideScreen && uiState.activeTabletThreadId == thread.id,
                                                compactDensity = uiState.settings.compactDensity,
                                                rightSwipeAction = uiState.settings.rightSwipeAction,
                                                leftSwipeAction = uiState.settings.leftSwipeAction,
                                                onClick = {
                                                    when {
                                                        uiState.isSelectionMode -> onToggleSelection(thread.id)
                                                        thread.folder == MailFolder.DRAFT -> onEditDraft(thread)
                                                        isWideScreen -> onSelectTabletThread(thread.id)
                                                        else -> onOpenThread(thread)
                                                    }
                                                },
                                                onLongClick = { onToggleSelection(thread.id) },
                                                onAvatarClick = { onToggleSelection(thread.id) },
                                                onToggleStar = { onToggleStar(thread) },
                                                onAttachmentClick = { previewAttachment = it },
                                                onSwipe = { action ->
                                                    if (action == uiState.settings.rightSwipeAction) {
                                                        onSwipeThread(thread)
                                                    } else {
                                                        onSwipeLeftThread(thread)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (isWideScreen) {
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            val activeTabletThread = uiState.visibleThreads.firstOrNull {
                                it.id == uiState.activeTabletThreadId
                            } ?: uiState.visibleThreads.firstOrNull()

                            ThreadDetailScreen(
                                thread = activeTabletThread,
                                availableLabels = uiState.availableLabels,
                                showBackButton = false,
                                onBack = { onSelectTabletThread(null) },
                                onArchive = onArchiveThread,
                                onDelete = onTrashThread,
                                onRestore = onRestoreThread,
                                onMarkUnread = onMarkThreadUnread,
                                onToggleStar = onToggleStar,
                                onToggleImportant = onToggleImportant,
                                onReportSpam = onReportSpam,
                                onSnooze = onSnoozeThread,
                                onUpdateLabels = onUpdateLabels,
                                onReply = onReply,
                                onReplyAll = onReplyAll,
                                onForward = onForward,
                                modifier = Modifier
                                    .weight(0.55f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAccountDialog) {
        AccountSwitcherDialog(
            activeAccount = uiState.activeAccount,
            allAccounts = uiState.allAccounts,
            onDismiss = { showAccountDialog = false },
            onSelectAccount = onSwitchAccount,
            onAddAccount = onAddAccount,
            onOpenSettings = onOpenSettings,
            onManageAccount = onManageAccount,
            discoveredAccounts = uiState.discoveredAccounts,
            onContinueAsDiscovered = onContinueAsDiscovered
        )
    }

    previewAttachment?.let { att ->
        AttachmentViewerDialog(
            attachment = att,
            onDismiss = { previewAttachment = null }
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("MailApp Help & Tips") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Swipe left or right on any message to Archive, Delete, or Mark Read.")
                    Text("• Tap a sender's circular avatar or long-press a row to select multiple emails.")
                    Text("• Pull down in your Inbox to sync incoming emails.")
                    Text("• Customize swipe actions, density, and Dark Mode in Settings.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("Got it") }
            }
        )
    }
}

@Composable
private fun PillSearchTopBar(
    activeAccountName: String,
    activeAccountColor: Long,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAccountSwitcher: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onOpenSearch)
            .testTag("pill_search_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.testTag("drawer_menu_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.cd_open_drawer),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.search_in_mail),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            )

            SenderAvatar(
                name = activeAccountName,
                colorHex = activeAccountColor,
                size = 32.dp,
                onClick = onOpenAccountSwitcher,
                modifier = Modifier.testTag("account_avatar_button")
            )
        }
    }
}

@Composable
private fun EmptyFolderState(folderName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.size(84.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Inbox,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.empty_folder_title, folderName),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.empty_folder_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
