package com.sshtool.app.di

import android.content.Context
import androidx.room.Room
import com.sshtool.app.data.db.*
import com.sshtool.app.data.ssh.SshManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DB_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConnectionDao(db: AppDatabase): ConnectionDao = db.connectionDao()

    @Provides
    fun provideSshKeyDao(db: AppDatabase): SshKeyDao = db.sshKeyDao()

    @Provides
    fun provideSnippetDao(db: AppDatabase): SnippetDao = db.snippetDao()

    @Provides
    fun provideKnownHostDao(db: AppDatabase): KnownHostDao = db.knownHostDao()

    @Provides
    fun providePortForwardDao(db: AppDatabase): PortForwardDao = db.portForwardDao()

    @Provides
    @Singleton
    fun provideSshManager(): SshManager = SshManager()
}
