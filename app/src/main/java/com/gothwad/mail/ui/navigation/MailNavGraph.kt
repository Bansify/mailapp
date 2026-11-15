package com.gothwad.mail.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gothwad.mail.ui.screens.ComposeScreen
import com.gothwad.mail.ui.screens.InboxScreen
import com.gothwad.mail.ui.screens.SearchScreen
import com.gothwad.mail.ui.screens.SettingsScreen
import com.gothwad.mail.ui.screens.ThreadDetailScreen
import com.gothwad.mail.ui.state.ComposeInitialData
import com.gothwad.mail.ui.state.MailUiState
import com.gothwad.mail.ui.viewmodel.MailViewModel
import kotlinx.serialization.Serializable

@Serializable
data object InboxRoute

@Serializable
data class ThreadDetailRoute(val threadId: Long)

@Serializable
data class ComposeRoute(
    val draftId: Long? = null,
    val replyToThreadId: Long? = null,
    val replyAll: Boolean = false,
    val forward: Boolean = false
)

@Serializable
data object SearchRoute

@Serializable
data object SettingsRoute

@Composable
fun MailNavGraph(
    viewModel: MailViewModel,
    uiState: MailUiState
) {
    val navController = rememberNavController()

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
                    viewModel.markThreadRead(thread.id, true)
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
                onAddAccount = viewModel::addAccount,
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
                remember { androidx.compose.runtime.mutableStateOf(null) }
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
                    viewModel.markThreadRead(thread.id, true)
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
                onBack = { navController.popBackStack() },
                onUpdateSettings = viewModel::updateSettings,
                onUpdateAccount = viewModel::updateAccountSettings
            )
        }
    }
}
