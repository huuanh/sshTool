package com.example.termiusclone.ssh

import com.example.termiusclone.data.db.HostEntity
import com.example.termiusclone.data.db.IdentityEntity
import com.example.termiusclone.data.db.KnownHostDao
import com.example.termiusclone.data.db.KnownHostEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey

/**
 * Thin wrapper around sshj's [SFTPClient] for one host. Performs the same
 * auth flow as [SshConnection] and uses the existing known_hosts table for
 * fingerprint verification (no UI prompts here — accept-on-first-use unless
 * strict, otherwise fail with a helpful message).
 */
class SftpSession(
    private val host: HostEntity,
    private val identity: IdentityEntity?,
    private val knownHostDao: KnownHostDao,
    private val strictHostKey: Boolean
) {
    private var client: SSHClient? = null
    private var sftp: SFTPClient? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        val c = SSHClient()
        c.addHostKeyVerifier(buildVerifier())
        c.connect(host.hostname, host.port)
        SshAuth.authenticate(c, host, identity)
        sftp = c.newSFTPClient()
        client = c
    }

    suspend fun list(path: String): List<RemoteResourceInfo> = withContext(Dispatchers.IO) {
        val s = sftp ?: error("SFTP not connected")
        s.ls(path).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun stat(path: String): FileAttributes = withContext(Dispatchers.IO) {
        sftp!!.stat(path)
    }

    suspend fun realPath(path: String): String = withContext(Dispatchers.IO) {
        sftp!!.canonicalize(path)
    }

    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        sftp!!.mkdir(path)
    }

    suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        sftp!!.rename(from, to)
    }

    suspend fun delete(path: String, isDir: Boolean) = withContext(Dispatchers.IO) {
        if (isDir) sftp!!.rmdir(path) else sftp!!.rm(path)
    }

    /** Stream a remote file to the given OutputStream. Returns total bytes. */
    suspend fun download(remotePath: String, sink: OutputStream): Long = withContext(Dispatchers.IO) {
        val s = sftp ?: error("SFTP not connected")
        var total = 0L
        s.open(remotePath).use { remote ->
            val rs = remote.RemoteFileInputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = rs.read(buf)
                if (n <= 0) break
                sink.write(buf, 0, n)
                total += n
            }
            sink.flush()
        }
        total
    }

    /** Stream a local InputStream to a remote path (overwrite). Returns total bytes. */
    suspend fun upload(remotePath: String, source: InputStream): Long = withContext(Dispatchers.IO) {
        val s = sftp ?: error("SFTP not connected")
        var total = 0L
        val flags = setOf(
            net.schmizz.sshj.sftp.OpenMode.WRITE,
            net.schmizz.sshj.sftp.OpenMode.CREAT,
            net.schmizz.sshj.sftp.OpenMode.TRUNC
        )
        s.open(remotePath, flags).use { remote ->
            val os = remote.RemoteFileOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buf)
                if (n <= 0) break
                os.write(buf, 0, n)
                total += n
            }
            os.flush()
        }
        total
    }

    fun close() {
        try { sftp?.close() } catch (_: Throwable) {}
        try { client?.disconnect() } catch (_: Throwable) {}
        sftp = null
        client = null
    }

    private fun buildVerifier(): HostKeyVerifier = object : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fp = net.schmizz.sshj.common.SecurityUtils.getFingerprint(key)
            val keyType = key.algorithm
            val existing = kotlinx.coroutines.runBlocking { knownHostDao.find(hostname, port) }
            if (existing != null) return existing.fingerprint == fp
            if (!strictHostKey) {
                kotlinx.coroutines.runBlocking {
                    knownHostDao.insert(KnownHostEntity(hostname, port, keyType, fp))
                }
                return true
            }
            // Strict mode: fail with hint to accept via terminal first.
            throw net.schmizz.sshj.transport.verification.HostKeyVerifier::class.java.let {
                IllegalStateException(
                    "Host key for $hostname:$port not trusted. Connect via Terminal first to verify the fingerprint, " +
                        "or disable Strict host key checking in Settings."
                )
            }
        }
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    companion object {
        fun isDirectory(info: RemoteResourceInfo): Boolean =
            info.attributes.type == FileMode.Type.DIRECTORY
        fun isSymlink(info: RemoteResourceInfo): Boolean =
            info.attributes.type == FileMode.Type.SYMLINK
    }
}
