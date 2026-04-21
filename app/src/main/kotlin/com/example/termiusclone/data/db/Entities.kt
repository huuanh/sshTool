package com.example.termiusclone.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "alias") val alias: String,
    @ColumnInfo(name = "hostname") val hostname: String,
    @ColumnInfo(name = "port") val port: Int = 22,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password") val password: String? = null,
    @ColumnInfo(name = "identity_id") val identityId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "identities")
data class IdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password") val password: String? = null,
    @ColumnInfo(name = "private_key") val privateKey: String? = null,
    @ColumnInfo(name = "passphrase") val passphrase: String? = null
)

@Entity(tableName = "known_hosts", primaryKeys = ["host", "port"])
data class KnownHostEntity(
    val host: String,
    val port: Int,
    @ColumnInfo(name = "key_type") val keyType: String,
    @ColumnInfo(name = "fingerprint") val fingerprint: String,
    @ColumnInfo(name = "first_seen") val firstSeen: Long = System.currentTimeMillis()
)
