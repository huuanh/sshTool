package com.example.termiusclone.ui.sftp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.termiusclone.App
import com.example.termiusclone.R
import com.example.termiusclone.databinding.ActivitySftpBinding
import com.example.termiusclone.ssh.SftpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.RemoteResourceInfo
import java.io.FileNotFoundException

class SftpActivity : AppCompatActivity() {

    private lateinit var b: ActivitySftpBinding
    private lateinit var adapter: SftpAdapter
    private var session: SftpSession? = null
    private var currentPath: String = "."
    private val pathStack = ArrayDeque<String>()

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) doUpload(uri)
    }

    private var pendingDownload: RemoteResourceInfo? = null
    private val createFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val item = pendingDownload
        pendingDownload = null
        if (uri != null && item != null) doDownload(item, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySftpBinding.inflate(layoutInflater)
        setContentView(b.root)

        val hostId = intent.getLongExtra(EXTRA_HOST_ID, 0L)
        if (hostId == 0L) { finish(); return }

        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.title = getString(R.string.sftp_title)
        b.toolbar.inflateMenu(R.menu.menu_sftp)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_mkdir -> { promptMkdir(); true }
                R.id.action_refresh -> { refresh(); true }
                else -> false
            }
        }

        adapter = SftpAdapter(
            onClick = { info -> onItemClick(info) },
            onLongClick = { info -> onItemLongClick(info) }
        )
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.fabUpload.setOnClickListener {
            try {
                pickFileLauncher.launch(arrayOf("*/*"))
            } catch (_: Throwable) {
                toast(getString(R.string.sftp_no_picker))
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pathStack.isNotEmpty()) {
                    val prev = pathStack.removeLast()
                    loadPath(prev, pushHistory = false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        lifecycleScope.launch { startSession(hostId) }
    }

    private suspend fun startSession(hostId: Long) {
        val db = App.get().db
        val host = db.hosts().byId(hostId) ?: run { finish(); return }
        val identity = host.identityId?.let { db.identities().byId(it) }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val strict = prefs.getBoolean("strict_host_key", true)
        val alias = host.alias.ifBlank { host.hostname }
        b.toolbar.subtitle = "${host.username}@${host.hostname}:${host.port}"
        b.toolbar.title = alias

        val s = SftpSession(host, identity, db.knownHosts(), strict)
        session = s
        showProgress(true)
        try {
            withContext(Dispatchers.IO) { s.connect() }
            val home = withContext(Dispatchers.IO) { s.realPath(".") }
            loadPath(home, pushHistory = false)
        } catch (e: Throwable) {
            toast(getString(R.string.sftp_error_prefix, e.message ?: e::class.java.simpleName))
            showProgress(false)
        }
    }

    private fun loadPath(path: String, pushHistory: Boolean) {
        val s = session ?: return
        if (pushHistory && currentPath != path) pathStack.addLast(currentPath)
        currentPath = path
        b.path.text = path
        showProgress(true)
        lifecycleScope.launch {
            try {
                val canon = withContext(Dispatchers.IO) { s.realPath(path) }
                currentPath = canon
                b.path.text = canon
                val items = withContext(Dispatchers.IO) { s.list(canon) }
                    .filter { it.name != "." && it.name != ".." }
                adapter.submit(items)
                b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Throwable) {
                toast(getString(R.string.sftp_error_prefix, e.message ?: e::class.java.simpleName))
            } finally {
                showProgress(false)
            }
        }
    }

    private fun refresh() = loadPath(currentPath, pushHistory = false)

    private fun onItemClick(info: RemoteResourceInfo) {
        if (SftpSession.isDirectory(info) || SftpSession.isSymlink(info)) {
            loadPath(joinPath(currentPath, info.name), pushHistory = true)
        } else {
            // Tap on file -> show actions
            onItemLongClick(info)
        }
    }

    private fun onItemLongClick(info: RemoteResourceInfo) {
        val isDir = SftpSession.isDirectory(info)
        val items = if (isDir) {
            arrayOf(
                getString(R.string.sftp_action_open),
                getString(R.string.sftp_action_rename),
                getString(R.string.sftp_action_delete)
            )
        } else {
            arrayOf(
                getString(R.string.sftp_action_download),
                getString(R.string.sftp_action_rename),
                getString(R.string.sftp_action_delete)
            )
        }
        AlertDialog.Builder(this)
            .setTitle(info.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (isDir) loadPath(joinPath(currentPath, info.name), pushHistory = true)
                    else startDownload(info)
                    1 -> promptRename(info)
                    2 -> confirmDelete(info)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun startDownload(info: RemoteResourceInfo) {
        pendingDownload = info
        try {
            createFileLauncher.launch(info.name)
        } catch (_: Throwable) {
            toast(getString(R.string.sftp_no_picker))
            pendingDownload = null
        }
    }

    private fun doDownload(info: RemoteResourceInfo, dest: Uri) {
        val s = session ?: return
        val remote = joinPath(currentPath, info.name)
        showProgress(true)
        lifecycleScope.launch {
            try {
                val total = withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(dest)?.use { os -> s.download(remote, os) }
                        ?: throw FileNotFoundException("Cannot open destination")
                }
                toast(getString(R.string.sftp_downloaded, total))
            } catch (e: Throwable) {
                toast(getString(R.string.sftp_error_prefix, e.message ?: e::class.java.simpleName))
            } finally {
                showProgress(false)
            }
        }
    }

    private fun doUpload(source: Uri) {
        val s = session ?: return
        val name = queryDisplayName(source) ?: "upload.bin"
        val remote = joinPath(currentPath, name)
        showProgress(true)
        lifecycleScope.launch {
            try {
                val total = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(source)?.use { ins -> s.upload(remote, ins) }
                        ?: throw FileNotFoundException("Cannot open source")
                }
                toast(getString(R.string.sftp_uploaded, name, total))
                refresh()
            } catch (e: Throwable) {
                toast(getString(R.string.sftp_error_prefix, e.message ?: e::class.java.simpleName))
            } finally {
                showProgress(false)
            }
        }
    }

    private fun promptMkdir() {
        val input = EditText(this)
        input.hint = getString(R.string.sftp_mkdir_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.sftp_action_mkdir)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val s = session ?: return@setPositiveButton
                val target = joinPath(currentPath, name)
                showProgress(true)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { s.mkdir(target) }
                        refresh()
                    } catch (e: Throwable) {
                        toast(getString(R.string.sftp_error_prefix, e.message ?: e::class.java.simpleName))
                        showProgress(false)
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptRename(info: RemoteResourceInfo) {
        val input = EditText(this).apply { setText(info.name); selectAll() }
        AlertDialog.Builder(this)
            .setTitle(R.string.sftp_action_rename)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == info.name) return@setPositiveButton
                val s = session ?: return@setPositiveButton
                val from = joinPath(currentPath, info.name)
                val to = joinPath(currentPath, newName)
                showProgress(true)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { s.rename(from, to) }
                        refresh()
                    } catch (e: Throwable) {
                        toast(getString(R.string.sftp_error_prefix, e.message ?: e::class.java.simpleName))
                        showProgress(false)
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(info: RemoteResourceInfo) {
        val isDir = SftpSession.isDirectory(info)
        AlertDialog.Builder(this)
            .setTitle(R.string.sftp_action_delete)
            .setMessage(getString(R.string.sftp_delete_confirm, info.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val s = session ?: return@setPositiveButton
                val target = joinPath(currentPath, info.name)
                showProgress(true)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { s.delete(target, isDir) }
                        refresh()
                    } catch (e: Throwable) {
                        toast(getString(R.string.sftp_error_prefix, e.message ?: e::class.java.simpleName))
                        showProgress(false)
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Throwable) { null }
    }

    private fun joinPath(base: String, name: String): String {
        if (name.startsWith("/")) return name
        if (name == "..") {
            val idx = base.trimEnd('/').lastIndexOf('/')
            return if (idx <= 0) "/" else base.substring(0, idx)
        }
        return if (base.endsWith("/")) base + name else "$base/$name"
    }

    private fun showProgress(show: Boolean) {
        b.progress.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    companion object {
        private const val EXTRA_HOST_ID = "host_id"
        fun intent(ctx: Context, hostId: Long): Intent =
            Intent(ctx, SftpActivity::class.java).putExtra(EXTRA_HOST_ID, hostId)

        @Suppress("unused")
        fun intentForResult(ctx: Activity, hostId: Long): Intent = intent(ctx, hostId)
    }
}
