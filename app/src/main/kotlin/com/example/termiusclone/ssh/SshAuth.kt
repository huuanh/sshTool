package com.example.termiusclone.ssh

import com.example.termiusclone.data.db.HostEntity
import com.example.termiusclone.data.db.IdentityEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.userauth.password.Resource

/**
 * Shared authentication logic used by both interactive shell sessions and
 * SFTP/file-transfer sessions. Tries publickey -> password -> keyboard-interactive
 * in order and aggregates errors into a helpful message.
 */
object SshAuth {

    suspend fun authenticate(
        client: SSHClient,
        host: HostEntity,
        identity: IdentityEntity?
    ): String = withContext(Dispatchers.IO) {
        val username = host.username.ifBlank { identity?.username ?: "root" }
        val key = identity?.privateKey?.takeIf { it.isNotBlank() }
        val pass = host.password?.takeIf { it.isNotBlank() }
            ?: identity?.password?.takeIf { it.isNotBlank() }

        if (key == null && pass == null) {
            error("No password or private key provided for host '${host.alias.ifBlank { host.hostname }}'")
        }

        val errors = mutableListOf<String>()

        if (key != null) {
            try {
                val passphrase = identity?.passphrase
                val keyProvider = if (passphrase.isNullOrEmpty()) {
                    client.loadKeys(key, null, null)
                } else {
                    client.loadKeys(key, null, PasswordUtils.createOneOff(passphrase.toCharArray()))
                }
                client.authPublickey(username, keyProvider)
                return@withContext username
            } catch (e: Throwable) {
                errors += "publickey: ${e.message ?: e::class.java.simpleName}"
            }
        }

        if (pass != null) {
            try {
                client.authPassword(username, pass)
                return@withContext username
            } catch (e: Throwable) {
                errors += "password: ${e.message ?: e::class.java.simpleName}"
            }
            try {
                val finder = object : PasswordFinder {
                    override fun reqPassword(resource: Resource<*>?): CharArray = pass.toCharArray()
                    override fun shouldRetry(resource: Resource<*>?): Boolean = false
                }
                client.auth(username, AuthKeyboardInteractive(PasswordResponseProvider(finder)))
                return@withContext username
            } catch (e: Throwable) {
                errors += "keyboard-interactive: ${e.message ?: e::class.java.simpleName}"
            }
        }

        val allowed = try {
            client.userAuth?.allowedMethods?.joinToString(",") ?: "unknown"
        } catch (_: Throwable) { "unknown" }

        val hint = when {
            key != null && pass == null ->
                " Hint: server rejected the key. Check the public key is in ~/.ssh/authorized_keys, " +
                    "the username '$username' is correct, or add a password as fallback."
            key == null && pass != null ->
                " Hint: server rejected the password. Check username '$username' or use a key."
            else -> ""
        }

        error("Authentication failed (user='$username', server allows: $allowed). " +
            "Tried: ${errors.joinToString("; ")}.$hint")
    }
}
