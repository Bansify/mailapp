package com.bansify.mail.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bansify.mail.ui.screens.ComposeScreen
import com.bansify.mail.ui.screens.InboxScreen
import com.bansify.mail.ui.screens.SearchScreen
import com.bansify.mail.ui.screens.SettingsScreen
import com.bansify.mail.ui.screens.NativeAuthSheet
import com.bansify.accounts.ui.GothwadSignInScreen
import com.bansify.mail.ui.state.NativeAuthStep
import com.bansify.mail.ui.screens.ThreadDetailScreen
import com.bansify.mail.ui.state.AuthScreenState
import com.bansify.mail.ui.state.ComposeInitialData
import com.bansify.mail.ui.state.MailUiState
import com.bansify.mail.ui.viewmodel.MailViewModel
import kotlinx.serialization.Serializable

@Serializable
data object InboxRoute

@Serializable
data class ThreadDetailRoute(val threadId: String)

@Serializable
data class ComposeRoute(
    val draftId: String? = null,
    val replyToThreadId: String? = null,
    val replyAll: Boolean = false,
    val forward: Boolean = false
)

@Serializable
data object SearchRoute

@Serializable
data object SettingsRoute

@Serializable
data object SignedOutRoute

/**
 * Root navigation.
 *
 * With no signed-in Bansify account the app shows the Signed-Out welcome screen only; it never
 * falls back to prototype data. Once an account holds a Mail grant the full mail experience is
 * shown, with the same Material 3 drawer, bottom navigation and adaptive two-pane layout as before.
 */
@Composable
fun MailNavGraph(
    viewModel: MailViewModel,
    uiState: MailUiState
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    if (uiState.authState == AuthScreenState.SIGNED_OUT && uiState.allAccounts.isEmpty()) {
        // The shared Bansify Accounts SDK renders the exact same sign-in / sign-up / MFA /
        // peer-SSO surface that every other Bansify app shows, so there is only one login UI to
        // maintain. It returns as soon as an account is authorized.
        GothwadSignInScreen(
            appName = "Bansify Mail",
            onSignedInComplete = { viewModel.refreshDiscoveredAccounts() },
            onClose = { },
        )
        return
    }

    NavHost(
        navController = navController,
        startDestination = InboxRoute
    ) {
        composable<InboxRoute> {
            InboxScreen(
                uiState = uiState,
                snackbarEvents = viewModel.snackbarEvents,
                onSelectFolder = viewModel::selectFolder,
                onSelectLabel = viewModel::selectLabel,
                onSelectBottomTab = viewModel::selectBottomTab,
                onToggleQuickFilter = viewModel::toggleQuickFilter,
                onRefresh = viewModel::refreshInbox,
                onOpenThread = { thread ->
                    viewModel.openThread(thread.id)
                    navController.navigate(ThreadDetailRoute(thread.id))
                },
                onSelectTabletThread = viewModel::selectTabletThread,
                onEditDraft = { draft ->
                    navController.navigate(ComposeRoute(draftId = draft.id))
                },
                onToggleSelection = viewModel::toggleThreadSelection,
                onClearSelection = viewModel::clearSelection,
                onSelectAll = viewModel::selectAllVisible,
                onArchiveSelected = viewModel::archiveSelected,
                onTrashSelected = viewModel::trashSelected,
                onMarkSelectedRead = viewModel::markSelectedRead,
                onToggleStar = viewModel::toggleStar,
                onSwipeThread = { thread ->
                    viewModel.handleSwipe(thread, uiState.settings.rightSwipeAction)
                },
                onSwipeLeftThread = { thread ->
                    viewModel.handleSwipe(thread, uiState.settings.leftSwipeAction)
                },
                onArchiveThread = { thread -> viewModel.archiveThreads(listOf(thread)) },
                onTrashThread = { thread -> viewModel.trashThreads(listOf(thread)) },
                onRestoreThread = { thread -> viewModel.undoFolderMove(listOf(thread)) },
                onMarkThreadUnread = { thread -> viewModel.markThreadRead(thread.id, false) },
                onToggleImportant = viewModel::toggleImportant,
                onReportSpam = viewModel::reportSpam,
                onSnoozeThread = viewModel::snoozeThread,
                onUpdateLabels = viewModel::updateLabels,
                onReply = { thread ->
                    navController.navigate(ComposeRoute(replyToThreadId = thread.id))
                },
                onReplyAll = { thread ->
                    navController.navigate(ComposeRoute(replyToThreadId = thread.id, replyAll = true))
                },
                onForward = { thread ->
                    navController.navigate(ComposeRoute(replyToThreadId = thread.id, forward = true))
                },
                onUndoMove = viewModel::undoFolderMove,
                onEmptyCurrentFolder = viewModel::emptyCurrentFolder,
                onOpenCompose = { navController.navigate(ComposeRoute()) },
                onOpenSearch = {
                    viewModel.resetSearch()
                    navController.navigate(SearchRoute)
                },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onSwitchAccount = viewModel::switchAccount,
                onAddAccount = { viewModel.openNativeSignIn() },
                onManageAccount = { activity?.let { viewModel.openAccountManager(it) } },
                onContinueAsDiscovered = { discovered ->
                    activity?.let { viewModel.signInAsDiscovered(it, discovered) }
                },
                onCreateMeeting = viewModel::createNewMeeting,
                onJoinMeeting = viewModel::joinMeeting,
                onLeaveMeeting = viewModel::leaveMeeting
            )
        }

        composable<ThreadDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ThreadDetailRoute>()
            val thread by viewModel.observeThread(route.threadId).collectAsStateWithLifecycle(initialValue = null)

            ThreadDetailScreen(
                thread = thread,
                availableLabels = uiState.availableLabels,
                showBackButton = true,
                onBack = { navController.popBackStack() },
                onArchive = { viewModel.archiveThreads(listOf(it)) },
                onDelete = { viewModel.trashThreads(listOf(it)) },
                onRestore = { viewModel.undoFolderMove(listOf(it)) },
                onMarkUnread = { viewModel.markThreadRead(it.id, false) },
                onToggleStar = viewModel::toggleStar,
                onToggleImportant = viewModel::toggleImportant,
                onReportSpam = viewModel::reportSpam,
                onSnooze = viewModel::snoozeThread,
                onUpdateLabels = viewModel::updateLabels,
                onReply = { t ->
                    navController.navigate(ComposeRoute(replyToThreadId = t.id))
                },
                onReplyAll = { t ->
                    navController.navigate(ComposeRoute(replyToThreadId = t.id, replyAll = true))
                },
                onForward = { t ->
                    navController.navigate(ComposeRoute(replyToThreadId = t.id, forward = true))
                }
            )
        }

        composable<ComposeRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ComposeRoute>()
            val targetId = route.draftId ?: route.replyToThreadId
            val sourceThread by if (targetId != null) {
                viewModel.observeThread(targetId).collectAsStateWithLifecycle(initialValue = null)
            } else {
                remember { mutableStateOf(null) }
            }

            if (targetId == null || sourceThread != null) {
                val initialData = remember(sourceThread, route) {
                    val t = sourceThread
                    when {
                        t != null && route.draftId != null -> viewModel.buildComposeFromDraft(t)
                        t != null && route.replyToThreadId != null ->
                            viewModel.buildComposeForReply(t, route.replyAll, route.forward)
                        else -> ComposeInitialData()
                    }
                }

                ComposeScreen(
                    initialData = initialData,
                    activeAccount = uiState.activeAccount,
                    allAccounts = uiState.allAccounts,
                    onBackAndSaveDraft = { draftId, fromAcc, to, cc, subj, bodyText, atts ->
                        viewModel.saveDraft(draftId, fromAcc, to, cc, subj, bodyText, atts)
                        navController.popBackStack()
                    },
                    onDiscardAndExit = { navController.popBackStack() },
                    onSend = { draftId, replyId, fromAcc, to, cc, subj, bodyText, atts, confidential, scheduleMillis ->
                        viewModel.sendOrScheduleEmail(
                            draftId = draftId,
                            replyToThreadId = replyId,
                            fromAccount = fromAcc,
                            to = to,
                            cc = cc,
                            subject = subj,
                            body = bodyText,
                            attachments = atts,
                            isConfidential = confidential,
                            scheduledForMillis = scheduleMillis
                        )
                        navController.popBackStack()
                    }
                )
            }
        }

        composable<SearchRoute> {
            SearchScreen(
                searchState = uiState.searchState,
                results = uiState.searchResults,
                onBack = { navController.popBackStack() },
                onQueryChange = viewModel::updateSearchQuery,
                onCommitHistory = viewModel::commitSearchHistory,
                onClearHistory = viewModel::clearSearchHistory,
                onToggleFilter = viewModel::toggleSearchFilter,
                onOpenThread = { thread ->
                    viewModel.openThread(thread.id)
                    navController.navigate(ThreadDetailRoute(thread.id))
                },
                onToggleStar = viewModel::toggleStar,
                onSwipe = viewModel::handleSwipe
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                settings = uiState.settings,
                activeAccount = uiState.activeAccount,
                retention = uiState.retention,
                onBack = { navController.popBackStack() },
                onUpdateSettings = viewModel::updateSettings,
                onUpdateAccount = viewModel::updateAccountSettings,
                onUpdateRetention = viewModel::updateRetention,
                onSignOutOfMail = { uiState.activeAccount?.let(viewModel::signOutOfMail) },
                onRemoveAccountFromDevice = { uiState.activeAccount?.let(viewModel::removeAccountFromDevice) },
                onDisconnectFromServer = { uiState.activeAccount?.let(viewModel::disconnectFromServer) },
                onManageAccount = { activity?.let { viewModel.openAccountManager(it) } },
                onOpenPrivacyPolicy = { navController.popBackStack() }
            )
        }
    }
}
