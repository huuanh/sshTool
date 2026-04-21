package com.example.termiusclone.ui.terminal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.termiusclone.App
import com.example.termiusclone.R
import com.example.termiusclone.databinding.ActivityTerminalBinding
import com.example.termiusclone.ssh.SshConnection
import com.example.termiusclone.ssh.SshState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TerminalActivity : AppCompatActivity() {

    private lateinit var b: ActivityTerminalBinding
    private var conn: SshConnection? = null
    private var pendingDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(b.root)

        val hostId = intent.getLongExtra(EXTRA_HOST_ID, 0L)
        if (hostId == 0L) { finish(); return }

        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnSend.setOnClickListener { sendInput() }
        b.btnCtrlC.setOnClickListener { conn?.sendCtrlC() }
        b.keyEsc.setOnClickListener { conn?.sendRaw("\u001B") }
        b.keyTab.setOnClickListener { conn?.sendRaw("\t") }
        b.keyUp.setOnClickListener { conn?.sendRaw("\u001B[A") }
        b.keyDown.setOnClickListener { conn?.sendRaw("\u001B[B") }
        b.keyRight.setOnClickListener { conn?.sendRaw("\u001B[C") }
        b.keyLeft.setOnClickListener { conn?.sendRaw("\u001B[D") }
        b.keyCtrlD.setOnClickListener { conn?.sendRaw("\u0004") }
        b.keyCtrlL.setOnClickListener { conn?.sendRaw("\u000C") }
        b.keyCtrlZ.setOnClickListener { conn?.sendRaw("\u001A") }
        b.keyEnter.setOnClickListener { conn?.sendRaw("\n") }
        b.input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                sendInput(); true
            } else false
        }

        applyFontSize()
        applyTheme()

        b.toolbar.inflateMenu(R.menu.menu_terminal)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_send_snippet -> { showSnippetPicker(); true }
                R.id.action_clear_screen -> { committed.setLength(0); currentLine.setLength(0); cursorCol = 0; flush(); true }
                else -> false
            }
        }

        lifecycleScope.launch { startSession(hostId) }

        // If launched with a pre-baked command, push it after Connected.
        intent.getStringExtra(EXTRA_INITIAL_COMMAND)?.let { initial ->
            lifecycleScope.launch {
                conn?.state?.first { it is SshState.Connected }
                conn?.send(initial.trimEnd('\n') + "\n")
            }
        }
    }

    private fun applyTheme() {
        val theme = TerminalThemes.current(this)
        b.outputScroll.setBackgroundColor(theme.bg)
        b.output.setBackgroundColor(theme.bg)
        b.output.setTextColor(theme.fg)
    }

    private fun showSnippetPicker() {
        lifecycleScope.launch {
            val list = App.get().db.snippets().all()
            if (list.isEmpty()) {
                AlertDialog.Builder(this@TerminalActivity)
                    .setMessage(R.string.snippet_none_yet)
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
                return@launch
            }
            AlertDialog.Builder(this@TerminalActivity)
                .setTitle(R.string.snippet_pick_title)
                .setItems(list.map { it.name }.toTypedArray()) { _, which ->
                    conn?.send(list[which].command.trimEnd('\n') + "\n")
                }
                .show()
        }
    }

    private fun applyFontSize() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val size = prefs.getInt("terminal_font_size", 13)
        b.output.textSize = size.toFloat()
        b.input.textSize = size.toFloat()
    }

    private suspend fun startSession(hostId: Long) {
        val db = App.get().db
        val host = db.hosts().byId(hostId) ?: run { finish(); return }
        val identity = host.identityId?.let { db.identities().byId(it) }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val strict = prefs.getBoolean("strict_host_key", true)
        val keepAlive = prefs.getString("keep_alive_sec", "0")?.toIntOrNull() ?: 0
        val autoReconnect = prefs.getBoolean("auto_reconnect", false)
        val alias = host.alias.ifBlank { host.hostname }
        b.toolbar.title = "$alias — ${host.username}@${host.hostname}:${host.port}"

        val c = SshConnection(host, identity, db.knownHosts(), strict, keepAlive, autoReconnect)
        conn = c

        lifecycleScope.launch {
            c.state.collectLatest { state ->
                when (state) {
                    SshState.Idle -> Unit
                    SshState.Connecting -> b.status.text = getString(R.string.terminal_status_connecting)
                    SshState.Connected -> b.status.text = getString(R.string.terminal_status_connected)
                    SshState.Disconnected -> b.status.text = getString(R.string.terminal_status_disconnected)
                    is SshState.Error -> b.status.text = getString(R.string.terminal_status_error, state.message)
                    is SshState.FingerprintPrompt -> showFingerprintDialog(state)
                }
            }
        }

        lifecycleScope.launch {
            c.output.collect { chunk ->
                feed(chunk)
                b.outputScroll.post { b.outputScroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }

        c.connect()
    }

    /**
     * Tiny terminal emulator: keeps a "committed" buffer for everything above
     * the current line and a mutable [currentLine] with a cursor position so
     * cursor-movement + overwrite sequences (used by readline for ↑/↓/←/→ and
     * line redraws) work correctly.
     */
    private enum class EscState { TEXT, ESC, CSI, OSC, OSC_ESC, CHARSET }
    private var escState: EscState = EscState.TEXT
    private val csiParams = StringBuilder()
    private val committed = StringBuilder()
    private val currentLine = StringBuilder()
    private var cursorCol: Int = 0

    private fun feed(s: String) {
        for (raw in s) {
            when (escState) {
                EscState.TEXT -> {
                    if (raw == '\u001B') escState = EscState.ESC
                    else handleText(raw)
                }
                EscState.ESC -> when (raw) {
                    '[' -> { escState = EscState.CSI; csiParams.setLength(0) }
                    ']' -> escState = EscState.OSC
                    '(', ')' -> escState = EscState.CHARSET
                    '=', '>', '7', '8', 'N', 'D', 'E', 'H', 'M', 'c', 'Z' -> escState = EscState.TEXT
                    '\u001B' -> { /* stay */ }
                    else -> escState = EscState.TEXT
                }
                EscState.CSI -> {
                    if (raw.code in 0x40..0x7E) {
                        handleCsi(raw)
                        csiParams.setLength(0)
                        escState = EscState.TEXT
                    } else {
                        csiParams.append(raw)
                    }
                }
                EscState.OSC -> when (raw) {
                    '\u0007' -> escState = EscState.TEXT
                    '\u001B' -> escState = EscState.OSC_ESC
                }
                EscState.OSC_ESC -> escState = EscState.TEXT
                EscState.CHARSET -> escState = EscState.TEXT
            }
        }
        flush()
    }

    private fun handleText(c: Char) {
        when (c) {
            '\u0007' -> { /* BEL */ }
            '\n' -> {
                committed.append(currentLine).append('\n')
                currentLine.setLength(0)
                cursorCol = 0
            }
            '\r' -> { cursorCol = 0 }
            '\b' -> { if (cursorCol > 0) cursorCol-- }
            else -> {
                if (cursorCol < currentLine.length) {
                    currentLine.setCharAt(cursorCol, c)
                } else {
                    // Pad if cursor was moved past end (rare)
                    while (currentLine.length < cursorCol) currentLine.append(' ')
                    currentLine.append(c)
                }
                cursorCol++
            }
        }
    }

    private fun parseFirstParam(default: Int = 0): Int {
        if (csiParams.isEmpty()) return default
        val s = csiParams.toString().trimStart('?', '>', '!')
        val end = s.indexOfFirst { !it.isDigit() }.let { if (it < 0) s.length else it }
        if (end == 0) return default
        return s.substring(0, end).toIntOrNull() ?: default
    }

    private fun handleCsi(finalByte: Char) {
        when (finalByte) {
            'K' -> {
                // Erase in Line. Default 0 = cursor to end.
                when (parseFirstParam(0)) {
                    0 -> if (cursorCol < currentLine.length) currentLine.setLength(cursorCol)
                    1 -> {
                        // start to cursor — replace with spaces
                        for (i in 0 until minOf(cursorCol, currentLine.length)) {
                            currentLine.setCharAt(i, ' ')
                        }
                    }
                    2 -> { currentLine.setLength(0); cursorCol = 0 }
                }
            }
            'J' -> {
                // Erase in Display. We only model current line + below.
                when (parseFirstParam(0)) {
                    0 -> if (cursorCol < currentLine.length) currentLine.setLength(cursorCol)
                    2, 3 -> {
                        committed.setLength(0)
                        currentLine.setLength(0)
                        cursorCol = 0
                    }
                }
            }
            'D' -> { // cursor back N
                val n = parseFirstParam(1).coerceAtLeast(1)
                cursorCol = (cursorCol - n).coerceAtLeast(0)
            }
            'C' -> { // cursor forward N
                val n = parseFirstParam(1).coerceAtLeast(1)
                cursorCol = (cursorCol + n).coerceAtMost(currentLine.length)
            }
            'G' -> { // cursor to column N (1-based)
                val n = parseFirstParam(1).coerceAtLeast(1)
                cursorCol = (n - 1).coerceAtLeast(0)
            }
            'H', 'f' -> { // cursor position — collapse to column only
                val s = csiParams.toString()
                val semi = s.indexOf(';')
                val colStr = if (semi >= 0) s.substring(semi + 1) else "1"
                val col = colStr.toIntOrNull() ?: 1
                cursorCol = (col - 1).coerceAtLeast(0)
            }
            'P' -> { // delete N chars
                val n = parseFirstParam(1).coerceAtLeast(1)
                if (cursorCol < currentLine.length) {
                    val end = (cursorCol + n).coerceAtMost(currentLine.length)
                    currentLine.delete(cursorCol, end)
                }
            }
            '@' -> { // insert N blanks
                val n = parseFirstParam(1).coerceAtLeast(1)
                if (cursorCol <= currentLine.length) {
                    val pad = CharArray(n) { ' ' }
                    currentLine.insert(cursorCol, pad)
                }
            }
            // 'm' (SGR colors), 'h'/'l' (modes), etc. — silently ignored
        }
    }

    private fun flush() {
        b.output.text = committed.toString() + currentLine.toString()
    }

    private fun showFingerprintDialog(prompt: SshState.FingerprintPrompt) {
        pendingDialog?.dismiss()
        pendingDialog = AlertDialog.Builder(this)
            .setTitle(R.string.terminal_fingerprint_dialog_title)
            .setMessage(getString(R.string.terminal_fingerprint_dialog_msg,
                "${prompt.host}:${prompt.port}", "${prompt.keyType}\n${prompt.fingerprint}"))
            .setPositiveButton(R.string.action_accept) { _, _ -> prompt.accept() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> prompt.reject() }
            .setCancelable(false)
            .show()
    }

    private fun sendInput() {
        val text = b.input.text.toString()
        if (text.isEmpty()) return
        conn?.send(text + "\n")
        b.input.setText("")
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingDialog?.dismiss()
        conn?.disconnect()
    }

    companion object {
        private const val EXTRA_HOST_ID = "host_id"
        private const val EXTRA_INITIAL_COMMAND = "initial_command"
        fun intent(ctx: Context, hostId: Long) =
            Intent(ctx, TerminalActivity::class.java).putExtra(EXTRA_HOST_ID, hostId)
        fun intentWithCommand(ctx: Context, hostId: Long, command: String) =
            Intent(ctx, TerminalActivity::class.java)
                .putExtra(EXTRA_HOST_ID, hostId)
                .putExtra(EXTRA_INITIAL_COMMAND, command)
    }
}
