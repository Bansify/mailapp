package com.gothwad.mail.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.gothwad.mail.data.model.AppSettingsEntity
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailAccountEntity

@Database(
    entities = [
        EmailThreadEntity::class,
        MailAccountEntity::class,
        AppSettingsEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(MailTypeConverters::class)
abstract class MailDatabase : RoomDatabase() {
    abstract fun mailDao(): MailDao

    companion object {
        @Volatile
        private var INSTANCE: MailDatabase? = null

        fun getInstance(context: Context): MailDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MailDatabase::class.java,
                    "mailapp_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
