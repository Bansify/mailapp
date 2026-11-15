package com.gothwad.mail.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.LabelImportant
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AllInbox
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Drafts
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.ReportGmailerrorred
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScheduleSend
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.ui.theme.DraftRed
import com.gothwad.mail.ui.theme.PromotionsGreen
import com.gothwad.mail.ui.theme.SocialBlue
import com.gothwad.mail.ui.theme.UpdatesAmber

@Composable
fun MailNavigationDrawerSheet(
    selectedFolder: MailFolder,
    selectedLabelFilter: String?,
    unreadCounts: Map<MailFolder, Int>,
    draftCount: Int,
    availableLabels: List<String>,
    onSelectFolder: (MailFolder) -> Unit,
    onSelectLabel: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalDrawerSheet(
        modifier = modifier.width(304.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            // Brand Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MailOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "MailApp",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            DrawerFolderItem(
                label = "All inboxes",
                icon = Icons.Rounded.AllInbox,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.ALL_INBOXES,
                badgeText = unreadCounts[MailFolder.ALL_INBOXES]?.toString(),
                onClick = { onSelectFolder(MailFolder.ALL_INBOXES) }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )

            DrawerFolderItem(
                label = "Primary",
                icon = Icons.Rounded.Inbox,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.PRIMARY,
                badgeText = unreadCounts[MailFolder.PRIMARY]?.toString(),
                onClick = { onSelectFolder(MailFolder.PRIMARY) }
            )
            DrawerFolderItem(
                label = "Promotions",
                icon = Icons.Rounded.LocalOffer,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.PROMOTIONS,
                badgeText = unreadCounts[MailFolder.PROMOTIONS]?.let { "$it new" },
                badgeColor = PromotionsGreen,
                onClick = { onSelectFolder(MailFolder.PROMOTIONS) }
            )
            DrawerFolderItem(
                label = "Social",
                icon = Icons.Rounded.Group,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.SOCIAL,
                badgeText = unreadCounts[MailFolder.SOCIAL]?.let { "$it new" },
                badgeColor = SocialBlue,
                onClick = { onSelectFolder(MailFolder.SOCIAL) }
            )
            DrawerFolderItem(
                label = "Updates",
                icon = Icons.Rounded.Info,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.UPDATES,
                badgeText = unreadCounts[MailFolder.UPDATES]?.let { "$it new" },
                badgeColor = UpdatesAmber,
                onClick = { onSelectFolder(MailFolder.UPDATES) }
            )

            Text(
                text = "All labels",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp)
            )

            DrawerFolderItem(
                label = "Starred",
                icon = Icons.Rounded.StarOutline,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.STARRED,
                badgeText = unreadCounts[MailFolder.STARRED]?.toString(),
                onClick = { onSelectFolder(MailFolder.STARRED) }
            )
            DrawerFolderItem(
                label = "Snoozed",
                icon = Icons.Rounded.Schedule,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.SNOOZED,
                onClick = { onSelectFolder(MailFolder.SNOOZED) }
            )
            DrawerFolderItem(
                label = "Important",
                icon = Icons.AutoMirrored.Rounded.LabelImportant,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.IMPORTANT,
                badgeText = unreadCounts[MailFolder.IMPORTANT]?.toString(),
                onClick = { onSelectFolder(MailFolder.IMPORTANT) }
            )
            DrawerFolderItem(
                label = "Sent",
                icon = Icons.AutoMirrored.Rounded.Send,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.SENT,
                onClick = { onSelectFolder(MailFolder.SENT) }
            )
            DrawerFolderItem(
                label = "Scheduled",
                icon = Icons.Rounded.ScheduleSend,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.SCHEDULED,
                onClick = { onSelectFolder(MailFolder.SCHEDULED) }
            )
            DrawerFolderItem(
                label = "Drafts",
                icon = Icons.Rounded.Drafts,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.DRAFT,
                badgeText = if (draftCount > 0) draftCount.toString() else null,
                badgeColor = DraftRed,
                onClick = { onSelectFolder(MailFolder.DRAFT) }
            )
            DrawerFolderItem(
                label = "All mail",
                icon = Icons.Rounded.MailOutline,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.ALL_MAIL,
                badgeText = unreadCounts[MailFolder.ALL_MAIL]?.toString(),
                onClick = { onSelectFolder(MailFolder.ALL_MAIL) }
            )
            DrawerFolderItem(
                label = "Spam",
                icon = Icons.Rounded.ReportGmailerrorred,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.SPAM,
                badgeText = unreadCounts[MailFolder.SPAM]?.toString(),
                onClick = { onSelectFolder(MailFolder.SPAM) }
            )
            DrawerFolderItem(
                label = "Trash",
                icon = Icons.Rounded.DeleteOutline,
                selected = selectedLabelFilter == null && selectedFolder == MailFolder.TRASH,
                onClick = { onSelectFolder(MailFolder.TRASH) }
            )

            Text(
                text = "Labels",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp)
            )

            availableLabels.forEach { label ->
                DrawerFolderItem(
                    label = label,
                    icon = Icons.Rounded.Label,
                    selected = selectedLabelFilter == label,
                    onClick = { onSelectLabel(label) }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )

            DrawerFolderItem(
                label = "Settings",
                icon = Icons.Rounded.Settings,
                selected = false,
                onClick = onOpenSettings
            )
            DrawerFolderItem(
                label = "Help & feedback",
                icon = Icons.AutoMirrored.Rounded.HelpOutline,
                selected = false,
                onClick = onOpenHelp
            )
        }
    }
}

@Composable
private fun DrawerFolderItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    badgeText: String? = null,
    badgeColor: Color? = null,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        },
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(21.dp)
            )
        },
        badge = badgeText?.let { text ->
            {
                if (badgeColor != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = badgeColor
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
            unselectedContainerColor = Color.Transparent
        ),
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .height(48.dp)
            .testTag("drawer_item_${label.lowercase().replace(" ", "_")}")
    )
}
