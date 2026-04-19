package com.sshtool.app.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sshtool.app.data.db.ConnectionEntity
import com.sshtool.app.data.db.SshKeyEntity
import com.sshtool.app.data.repository.ConnectionRepository
import com.sshtool.app.data.repository.SshKeyRepository
import com.sshtool.app.data.security.CryptoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val repository: ConnectionRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    val connections: StateFlow<List<ConnectionEntity>> = repository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val keys: StateFlow<List<SshKeyEntity>> = sshKeyRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(connection: ConnectionEntity) {
        viewModelScope.launch { repository.delete(connection) }
    }

    fun save(
        id: Long,
        name: String,
        host: String,
        port: Int,
        username: String,
        authMethod: String,
        password: String?,
        privateKeyId: Long?
    ) {
        viewModelScope.launch {
            val entity = ConnectionEntity(
                id = if (id == -1L) 0 else id,
                name = name.ifBlank { "$username@$host" },
                host = host,
                port = port,
                username = username,
                authMethod = authMethod,
                password = password?.let { cryptoManager.encrypt(it) },
                privateKeyId = privateKeyId
            )
            if (id == -1L) {
                repository.insert(entity)
            } else {
                repository.update(entity)
            }
        }
    }

    suspend fun getById(id: Long): ConnectionEntity? {
        val entity = repository.getById(id) ?: return null
        // Decrypt password for display in edit form
        return entity.copy(
            password = entity.password?.let { cryptoManager.decrypt(it) }
        )
    }
}
