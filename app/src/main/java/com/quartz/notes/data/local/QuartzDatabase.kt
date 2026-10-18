package com.quartz.notes.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.quartz.notes.data.local.dao.NoteDao
import com.quartz.notes.data.local.entity.*

@Database(
    entities = [
        FolderEntity::class,
        NoteEntity::class,
        TagEntity::class,
        NoteTagCrossRef::class
    ],
    version = 1,
    exportSchema = false
)
abstract class QuartzDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: QuartzDatabase? = null

        fun getInstance(context: Context): QuartzDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    QuartzDatabase::class.java,
                    "quartz_notes.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
