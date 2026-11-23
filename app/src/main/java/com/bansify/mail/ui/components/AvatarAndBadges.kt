package com.bansify.mail.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bansify.mail.data.model.AttachmentItem
import com.bansify.mail.data.model.MailFolder
import com.bansify.mail.ui.theme.PromotionsGreen
import com.bansify.mail.ui.theme.SocialBlue
import com.bansify.mail.ui.theme.UpdatesAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * In-memory cache for domain and brand logos (e.g. Google, Instagram, Bansify, GitHub, Amazon).
 * Uses the Google Favicons service which resolves 128x128 brand icons for any corporate domain.
 */
object DomainLogoCache {
    private val memoryCache = object : LruCache<String, Bitmap>(100) {}
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val personalDomains = setOf(
        "gmail.com", "yahoo.com", "hotmail.com", "outlook.com",
        "icloud.com", "live.com", "aol.com", "proton.me", "protonmail.com"
    )

    fun isPersonalDomain(domain: String): Boolean = domain in personalDomains

    fun get(domain: String): Bitmap? = memoryCache.get(domain)

    suspend fun load(domain: String): Bitmap? = withContext(Dispatchers.IO) {
        if (domain.isBlank() || isPersonalDomain(domain)) return@withContext null
        memoryCache.get(domain)?.let { return@withContext it }
        val url = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            memoryCache.put(domain, bitmap)
                            return@withContext bitmap
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Ignore network or test environment errors gracefully
        }
        null
    }
}

@Composable
fun SenderAvatar(
    name: String,
    colorHex: Long,
    email: String? = null,
    isSelected: Boolean = false,
    size: Dp = 40.dp,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val baseModifier = modifier
        .then(
            if (onClick != null) {
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(onClick = onClick)
            } else {
                Modifier
            }
        )

    val domain = remember(email) {
        val d = email?.substringAfter('@', "")?.trim()?.lowercase().orEmpty()
        if (d.isNotEmpty() && !DomainLogoCache.isPersonalDomain(d)) d else null
    }

    var logoBitmap by remember(domain) {
        mutableStateOf(domain?.let { DomainLogoCache.get(it) })
    }

    LaunchedEffect(domain) {
        if (domain != null && logoBitmap == null) {
            val loaded = DomainLogoCache.load(domain)
            if (loaded != null) {
                logoBitmap = loaded
            }
        }
    }

    Box(
        modifier = baseModifier,
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = isSelected,
            transitionSpec = {
                (scaleIn(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn()) togetherWith
                    (scaleOut(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut())
            },
            label = "avatar_selection"
        ) { selected ->
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(size * 0.55f)
                    )
                }
            } else {
                val currentLogo = logoBitmap
                if (currentLogo != null) {
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = currentLogo.asImageBitmap(),
                            contentDescription = name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(size * 0.72f)
                                .clip(CircleShape)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(CircleShape)
                            .background(Color(colorHex)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = MailFormatters.extractInitials(name),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AttachmentPreviewChip(
    attachment: AttachmentItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon: ImageVector = when {
        attachment.mimeType.contains("pdf", ignoreCase = true) -> Icons.Rounded.PictureAsPdf
        attachment.mimeType.startsWith("image", ignoreCase = true) -> Icons.Rounded.Image
        else -> Icons.Rounded.Description
    }
    val iconTint = when {
        attachment.mimeType.contains("pdf", ignoreCase = true) -> Color(0xFFD93025)
        attachment.mimeType.startsWith("image", ignoreCase = true) -> Color(0xFF1A73E8)
        else -> Color(0xFF188038)
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LabelPill(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun CategoryTeaserRow(
    folder: MailFolder,
    unreadCount: Int,
    previewSenders: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, accentColor) = when (folder) {
        MailFolder.PROMOTIONS -> Icons.Rounded.LocalOffer to PromotionsGreen
        MailFolder.SOCIAL -> Icons.Rounded.Group to SocialBlue
        else -> Icons.Rounded.Info to UpdatesAmber
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("category_teaser_${folder.name.lowercase()}")
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            if (previewSenders.isNotBlank()) {
                Text(
                    text = previewSenders,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = accentColor
        ) {
            Text(
                text = "$unreadCount new",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
