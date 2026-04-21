package com.example.termiusclone.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HostEntity::class, IdentityEntity::class, KnownHostEntity::class, SnippetEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun identities(): IdentityDao
    abstract fun knownHosts(): KnownHostDao
    abstract fun snippets(): SnippetDao

    companion object {
        fun create(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "termius_clone.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
