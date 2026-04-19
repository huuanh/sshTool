package com.sshtool.app.data.repository

import com.sshtool.app.data.db.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepository @Inject constructor(
    private val connectionDao: ConnectionDao
) {
    fun getAll(): Flow<List<ConnectionEntity>> = connectionDao.getAll()
    suspend fun getById(id: Long) = connectionDao.getById(id)
    suspend fun insert(connection: ConnectionEntity) = connectionDao.insert(connection)
    suspend fun update(connection: ConnectionEntity) = connectionDao.update(connection)
    suspend fun delete(connection: ConnectionEntity) = connectionDao.delete(connection)
    suspend fun updateLastConnected(id: Long) =
        connectionDao.updateLastConnected(id, System.currentTimeMillis())
}

@Singleton
class SshKeyRepository @Inject constructor(
    private val sshKeyDao: SshKeyDao
) {
    fun getAll(): Flow<List<SshKeyEntity>> = sshKeyDao.getAll()
    suspend fun getById(id: Long) = sshKeyDao.getById(id)
    suspend fun insert(key: SshKeyEntity) = sshKeyDao.insert(key)
    suspend fun delete(key: SshKeyEntity) = sshKeyDao.delete(key)
}

@Singleton
class SnippetRepository @Inject constructor(
    private val snippetDao: SnippetDao
) {
    fun getAll(): Flow<List<SnippetEntity>> = snippetDao.getAll()
    suspend fun insert(snippet: SnippetEntity) = snippetDao.insert(snippet)
    suspend fun update(snippet: SnippetEntity) = snippetDao.update(snippet)
    suspend fun delete(snippet: SnippetEntity) = snippetDao.delete(snippet)
}

@Singleton
class KnownHostRepository @Inject constructor(
    private val knownHostDao: KnownHostDao
) {
    suspend fun get(host: String, port: Int) = knownHostDao.get("$host:$port")
    suspend fun save(host: String, port: Int, fingerprint: String, keyType: String) =
        knownHostDao.insert(KnownHostEntity("$host:$port", fingerprint, keyType))
    suspend fun delete(host: String, port: Int) {
        knownHostDao.get("$host:$port")?.let { knownHostDao.delete(it) }
    }
    fun getAll(): Flow<List<KnownHostEntity>> = knownHostDao.getAll()
}

@Singleton
class PortForwardRepository @Inject constructor(
    private val portForwardDao: PortForwardDao
) {
    fun getByConnection(connectionId: Long) = portForwardDao.getByConnection(connectionId)
    suspend fun insert(pf: PortForwardEntity) = portForwardDao.insert(pf)
    suspend fun delete(pf: PortForwardEntity) = portForwardDao.delete(pf)
}
