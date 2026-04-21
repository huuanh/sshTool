package com.example.termiusclone.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY alias COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun byId(id: Long): HostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HostEntity): Long

    @Update
    suspend fun update(entity: HostEntity)

    @Delete
    suspend fun delete(entity: HostEntity)
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<IdentityEntity>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun byId(id: Long): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: IdentityEntity): Long

    @Update
    suspend fun update(entity: IdentityEntity)

    @Delete
    suspend fun delete(entity: IdentityEntity)
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts WHERE host = :host AND port = :port LIMIT 1")
    suspend fun find(host: String, port: Int): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: KnownHostEntity)

    @Query("SELECT * FROM known_hosts ORDER BY host ASC")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("DELETE FROM known_hosts WHERE host = :host AND port = :port")
    suspend fun remove(host: String, port: Int)
}
