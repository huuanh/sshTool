package com.example.termiusclone.ssh

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/**
 * Generate an in-app RSA-2048 keypair.
 *
 * Returns:
 *  - privatePem: PKCS#8 PEM (sshj loads via "-----BEGIN PRIVATE KEY-----")
 *  - publicSsh : ssh-rsa one-liner suitable for ~/.ssh/authorized_keys
 */
object KeyGen {

    data class Generated(val privatePem: String, val publicSsh: String, val type: String)

    fun generateRsa(comment: String = "termius-clone"): Generated {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        return Generated(
            privatePem = toPkcs8Pem(kp),
            publicSsh = toSshRsa(kp.public as RSAPublicKey, comment),
            type = "ssh-rsa"
        )
    }

    private fun toPkcs8Pem(kp: KeyPair): String {
        val der = kp.private.encoded ?: error("private key encoding unavailable")
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val wrapped = b64.chunked(64).joinToString("\n")
        return "-----BEGIN PRIVATE KEY-----\n$wrapped\n-----END PRIVATE KEY-----\n"
    }

    private fun toSshRsa(pub: RSAPublicKey, comment: String): String {
        // wire format: string "ssh-rsa", mpint e, mpint n
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        writeString(out, "ssh-rsa".toByteArray())
        writeString(out, mpint(pub.publicExponent.toByteArray()))
        writeString(out, mpint(pub.modulus.toByteArray()))
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "ssh-rsa $b64 $comment"
    }

    private fun writeString(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    /** Ensure a positive mpint: prepend 0x00 if MSB is set. */
    private fun mpint(b: ByteArray): ByteArray {
        if (b.isEmpty()) return b
        if (b[0].toInt() and 0x80 == 0) return b
        val out = ByteArray(b.size + 1)
        out[0] = 0
        System.arraycopy(b, 0, out, 1, b.size)
        return out
    }

    init { Log.d("KeyGen", "ready") }
}
