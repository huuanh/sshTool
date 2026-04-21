package com.example.termiusclone

import android.app.Application
import android.util.Log
import com.example.termiusclone.data.db.AppDb
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class App : Application() {
    val db: AppDb by lazy { AppDb.create(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installBouncyCastle()
        installCrashGuard()
    }

    private fun installBouncyCastle() {
        // Android ships a stripped-down "BC" provider missing X25519/Ed25519/etc.
        // Replace it with the full bcprov-jdk18on we bundle so sshj can negotiate
        // modern key exchange and host-key algorithms.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    /**
     * sshj spawns its own Reader/Writer threads. When the SSH socket drops
     * (network change, server kill, app backgrounded long enough...) those
     * threads throw SSHException which is uncaught and would crash the app.
     * Swallow exceptions originating from sshj background threads.
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val name = thread.name ?: ""
            val msg = throwable.message ?: ""
            val isSshjThread = name.startsWith("sshj-") ||
                name.contains("Reader") ||
                name.contains("Writer") ||
                throwable.stackTrace.any { it.className.startsWith("net.schmizz.sshj") }
            val isTransientNet = msg.contains("Software caused connection abort", true) ||
                msg.contains("Connection reset", true) ||
                msg.contains("Broken pipe", true) ||
                msg.contains("EOF", true)
            if (isSshjThread || isTransientNet) {
                Log.w("App", "Suppressed background SSH error on '$name': $msg")
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        @Volatile
        private var instance: App? = null
        fun get(): App = instance ?: error("App not initialized")
    }
}
