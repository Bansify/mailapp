package com.bansify.mail.data.local

import androidx.room.TypeConverter
import com.bansify.mail.data.model.MailFolder
import com.bansify.mail.data.model.SwipeAction
import com.bansify.mail.data.model.ThemePreference
import com.bansify.mail.data.model.ThreadMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MailTypeConverters {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @TypeConverter
    fun fromThreadMessages(messages: List<ThreadMessage>): String = json.encodeToString(messages)

    @TypeConverter
    fun toThreadMessages(raw: String): List<ThreadMessage> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString<List<ThreadMessage>>(raw) }
            .getOrDefault(emptyList())

    @TypeConverter
    fun fromStringList(items: List<String>): String = json.encodeToString(items)

    @TypeConverter
    fun toStringList(raw: String): List<String> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())

    @TypeConverter
    fun fromMailFolder(folder: MailFolder): String = folder.name

    @TypeConverter
    fun toMailFolder(name: String): MailFolder =
        runCatching { MailFolder.valueOf(name) }.getOrDefault(MailFolder.PRIMARY)

    @TypeConverter
    fun fromThemePreference(pref: ThemePreference): String = pref.name

    @TypeConverter
    fun toThemePreference(name: String): ThemePreference =
        runCatching { ThemePreference.valueOf(name) }.getOrDefault(ThemePreference.SYSTEM)

    @TypeConverter
    fun fromSwipeAction(action: SwipeAction): String = action.name

    @TypeConverter
    fun toSwipeAction(name: String): SwipeAction =
        runCatching { SwipeAction.valueOf(name) }.getOrDefault(SwipeAction.ARCHIVE)
}
