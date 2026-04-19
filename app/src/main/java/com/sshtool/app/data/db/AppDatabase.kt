package com.sshtool.app.data.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ===== Entities =====

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: String = "password", // "password" or "key"
    val password: String? = null,
    val privateKeyId: Long? = null,
    val lastConnected: Long? = null,
    val sortOrder: Int = 0
)

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String = "RSA", // RSA, Ed25519
    val publicKey: String,
    val privateKey: String, // encrypted
    val passphrase: String? = null, // encrypted
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val command: String,
    val category: String = "",
    val sortOrder: Int = 0
)

@Entity(tableName = "known_hosts")
data class KnownHostEntity(
    @PrimaryKey val hostKey: String, // host:port
    val fingerprint: String,
    val keyType: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "port_forwards")
data class PortForwardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val connectionId: Long,
    val type: String = "local", // local, remote, dynamic
    val localPort: Int,
    val remoteHost: String = "localhost",
    val remotePort: Int,
    val enabled: Boolean = true
)

// ===== DAOs =====

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY sortOrder ASC, lastConnected DESC")
    fun getAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun getById(id: Long): ConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: ConnectionEntity): Long

    @Update
    suspend fun update(connection: ConnectionEntity)

    @Delete
    suspend fun delete(connection: ConnectionEntity)

    @Query("UPDATE connections SET lastConnected = :time WHERE id = :id")
    suspend fun updateLastConnected(id: Long, time: Long)
}

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY name ASC")
    fun getAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: Long): SshKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: SshKeyEntity): Long

    @Delete
    suspend fun delete(key: SshKeyEntity)
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY sortOrder ASC, name ASC")
    fun getAll(): Flow<List<SnippetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snippet: SnippetEntity): Long

    @Update
    suspend fun update(snippet: SnippetEntity)

    @Delete
    suspend fun delete(snippet: SnippetEntity)
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts WHERE hostKey = :hostKey")
    suspend fun get(hostKey: String): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(host: KnownHostEntity)

    @Delete
    suspend fun delete(host: KnownHostEntity)

    @Query("SELECT * FROM known_hosts ORDER BY addedAt DESC")
    fun getAll(): Flow<List<KnownHostEntity>>
}

@Dao
interface PortForwardDao {
    @Query("SELECT * FROM port_forwards WHERE connectionId = :connectionId")
    fun getByConnection(connectionId: Long): Flow<List<PortForwardEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pf: PortForwardEntity): Long

    @Delete
    suspend fun delete(pf: PortForwardEntity)
}

// ===== Database =====

@Database(
    entities = [
        ConnectionEntity::class,
        SshKeyEntity::class,
        SnippetEntity::class,
        KnownHostEntity::class,
        PortForwardEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun snippetDao(): SnippetDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun portForwardDao(): PortForwardDao

    companion object {
        const val DB_NAME = "sshtool.db"
    }
}
