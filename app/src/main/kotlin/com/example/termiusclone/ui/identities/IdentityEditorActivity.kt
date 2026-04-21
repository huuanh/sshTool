package com.example.termiusclone.ui.identities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.termiusclone.App
import com.example.termiusclone.data.db.IdentityEntity
import com.example.termiusclone.databinding.ActivityIdentityEditorBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

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

        if (editingId != 0L) {
            lifecycleScope.launch {
                App.get().db.identities().byId(editingId)?.let { id ->
                    b.inputName.setText(id.name)
                    b.inputUsername.setText(id.username)
                    b.inputPassword.setText(id.password ?: "")
                    b.inputPassphrase.setText(id.passphrase ?: "")
                    b.inputPrivateKey.setText(id.privateKey ?: "")
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
            passphrase = b.inputPassphrase.text.toString().takeIf { it.isNotEmpty() }
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

    companion object {
        private const val EXTRA_ID = "id"
        fun editIntent(ctx: Context, id: Long) =
            Intent(ctx, IdentityEditorActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
