package com.quartz.notes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ShelfEntity::class,
        NotebookEntity::class,
        PageEntity::class,
        PageElementEntity::class,
        QuickNoteEntity::class,
        AttachmentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class QuartzDatabase : RoomDatabase() {
    abstract fun shelfDao(): ShelfDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun quickNoteDao(): QuickNoteDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        fun build(context: Context): QuartzDatabase =
            Room.databaseBuilder(context, QuartzDatabase::class.java, "quartz_notes.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        db.execSQL(
                            "INSERT INTO shelves (name, position, createdAt) VALUES ('Notebooks', 0, ${System.currentTimeMillis()})",
                        )
                    }
                })
                .build()
    }
}
