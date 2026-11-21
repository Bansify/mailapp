package com.bansify.mail.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object MailFormatters {

    fun formatListTimestamp(timestampMillis: Long): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = timestampMillis }

        val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

        if (sameDay) {
            return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMillis))
        }

        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) return "Yesterday"

        val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
        val pattern = if (sameYear) "MMM d" else "MMM d, yyyy"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMillis))
    }

    fun formatFullTimestamp(timestampMillis: Long): String {
        return SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).format(Date(timestampMillis))
    }

    fun extractInitials(name: String): String {
        val cleaned = name.removePrefix("To:").trim()
        val parts = cleaned.split(" ").filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "M"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> "${parts.first().first()}${parts.last().first()}".uppercase()
        }
    }
}
