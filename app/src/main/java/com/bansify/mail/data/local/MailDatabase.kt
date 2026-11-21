package com.bansify.mail.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bansify.mail.data.model.AppSettingsEntity
import com.bansify.mail.data.model.EmailThreadEntity
import com.bansify.mail.data.model.MailAccountEntity

/**
 * Per-account mail cache.
 *
 * Version 2 replaces the seeded prototype schema with a server-keyed cache: every thread row now
 * carries its `accountEmail` plus the server message id, and the old seeded prototype tables are
 * dropped so no demo data can survive an upgrade.
 */
@Database(
    entities = [
        EmailThreadEntity::class,
        MailAccountEntity::class,
        AppSettingsEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(MailTypeConverters::class)
abstract class MailDatabase : RoomDatabase() {
    abstract fun mailDao(): MailDao

    companion object {
        private const val DATABASE_NAME = "mailapp_db"

        @Volatile
        private var INSTANCE: MailDatabase? = null

        /**
         * Prototype caches were seeded with fake accounts and threads and used auto-increment ids.
         * Dropping them guarantees a clean, account-scoped cache after the upgrade.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS email_threads")
                db.execSQL("DROP TABLE IF EXISTS accounts")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS email_threads (
                        id TEXT NOT NULL PRIMARY KEY,
                        accountEmail TEXT NOT NULL,
                        serverId TEXT NOT NULL,
                        subject TEXT NOT NULL,
                        snippet TEXT NOT NULL,
                        primarySenderName TEXT NOT NULL,
                        primarySenderEmail TEXT NOT NULL,
                        primarySenderColorHex INTEGER NOT NULL,
                        folder TEXT NOT NULL,
                        previousFolder TEXT NOT NULL,
                        isRead INTEGER NOT NULL,
                        isStarred INTEGER NOT NULL,
                        isImportant INTEGER NOT NULL,
                        isArchived INTEGER NOT NULL,
                        isConfidential INTEGER NOT NULL,
                        scheduledForMillis INTEGER,
                        snoozedUntilLabel TEXT,
                        labels TEXT NOT NULL,
                        messages TEXT NOT NULL,
                        bodyText TEXT NOT NULL,
                        bodyHtml TEXT,
                        etag TEXT,
                        bodyLoaded INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_email_threads_accountEmail_serverId " +
                        "ON email_threads (accountEmail, serverId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_email_threads_accountEmail_folder " +
                        "ON email_threads (accountEmail, folder)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_email_threads_accountEmail_updatedAtMillis " +
                        "ON email_threads (accountEmail, updatedAtMillis)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS accounts (
                        email TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        username TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        avatarColorHex INTEGER NOT NULL,
                        storageUsedBytes INTEGER NOT NULL,
                        storageQuotaBytes INTEGER NOT NULL,
                        totalMessages INTEGER NOT NULL,
                        starredMessages INTEGER NOT NULL,
                        retentionLimit INTEGER NOT NULL,
                        unreadCount INTEGER NOT NULL,
                        signature TEXT NOT NULL,
                        vacationEnabled INTEGER NOT NULL,
                        vacationMessage TEXT NOT NULL,
                        isActive INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): MailDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MailDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** Builds an isolated in-memory database; used by JVM tests. */
        fun inMemory(context: Context): MailDatabase =
            Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
