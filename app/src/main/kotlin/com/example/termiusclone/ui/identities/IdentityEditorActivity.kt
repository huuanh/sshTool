package com.example.termiusclone.ui.identities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.termiusclone.App
import com.example.termiusclone.R
import com.example.termiusclone.data.db.IdentityEntity
import com.example.termiusclone.databinding.ActivityIdentityEditorBinding
import com.example.termiusclone.ssh.KeyGen
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IdentityEditorActivity : AppCompatActivity() {

    private lateinit var b: ActivityIdentityEditorBinding
    private var editingId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityIdentityEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        editingId = intent.getLongExtra(EXTRA_ID, 0L)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnDelete.visibility = if (editingId != 0L) View.VISIBLE else View.GONE
        b.btnSave.setOnClickListener { save() }
        b.btnDelete.setOnClickListener { delete() }
        b.btnGenerateKey.setOnClickListener { generateKey() }

        if (editingId != 0L) {
            lifecycleScope.launch {
                App.get().db.identities().byId(editingId)?.let { id ->
                    b.inputName.setText(id.name)
                    b.inputUsername.setText(id.username)
                    b.inputPassword.setText(id.password ?: "")
                    b.inputPassphrase.setText(id.passphrase ?: "")
                    b.inputPrivateKey.setText(id.privateKey ?: "")
                    generatedPublicSsh = id.publicKey
                }
            }
        }
    }

    private fun save() {
        val name = b.inputName.text.toString().trim()
        val username = b.inputUsername.text.toString().trim()
        if (name.isEmpty() || username.isEmpty()) {
            Snackbar.make(b.root, "Name and username are required", Snackbar.LENGTH_LONG).show()
            return
        }
        val entity = IdentityEntity(
            id = editingId,
            name = name,
            username = username,
            password = b.inputPassword.text.toString().takeIf { it.isNotEmpty() },
            privateKey = b.inputPrivateKey.text.toString().takeIf { it.isNotBlank() },
            passphrase = b.inputPassphrase.text.toString().takeIf { it.isNotEmpty() },
            publicKey = generatedPublicSsh
        )
        lifecycleScope.launch {
            val dao = App.get().db.identities()
            if (editingId == 0L) dao.insert(entity) else dao.update(entity)
            finish()
        }
    }

    private fun delete() {
        if (editingId == 0L) return
        lifecycleScope.launch {
            val dao = App.get().db.identities()
            dao.byId(editingId)?.let { dao.delete(it) }
            finish()
        }
    }

    private var generatedPublicSsh: String? = null

    private fun generateKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.identity_generate_title)
            .setMessage(R.string.identity_generate_confirm)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                lifecycleScope.launch {
                    val gen = withContext(Dispatchers.Default) { KeyGen.generateRsa("termius-clone-${System.currentTimeMillis()}") }
                    b.inputPrivateKey.setText(gen.privatePem)
                    b.inputPassphrase.setText("")
                    generatedPublicSsh = gen.publicSsh
                    AlertDialog.Builder(this@IdentityEditorActivity)
                        .setTitle(R.string.identity_generate_done_title)
                        .setMessage(getString(R.string.identity_generate_done_msg, gen.publicSsh))
                        .setPositiveButton(R.string.action_copy) { _, _ ->
                            val cm = getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("public key", gen.publicSsh))
                            Snackbar.make(b.root, R.string.identity_public_copied, Snackbar.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(R.string.action_close, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun editIntent(ctx: Context, id: Long) =
            Intent(ctx, IdentityEditorActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
