package com.example.termiusclone.ui.hosteditor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.termiusclone.App
import com.example.termiusclone.R
import com.example.termiusclone.data.db.HostEntity
import com.example.termiusclone.data.db.IdentityEntity
import com.example.termiusclone.databinding.ActivityHostEditorBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class HostEditorActivity : AppCompatActivity() {

    private lateinit var b: ActivityHostEditorBinding
    private var editingId: Long = 0L
    private var identities: List<IdentityEntity> = emptyList()
    private var selectedIdentityId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHostEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        editingId = intent.getLongExtra(EXTRA_ID, 0L)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnDelete.visibility = if (editingId != 0L) android.view.View.VISIBLE else android.view.View.GONE

        b.btnSave.setOnClickListener { save() }
        b.btnDelete.setOnClickListener { delete() }
        b.dropdownIdentity.setOnItemClickListener { _, _, position, _ ->
            selectedIdentityId = if (position == 0) null else identities[position - 1].id
        }

        lifecycleScope.launch { loadIdentitiesAndHost() }
    }

    private suspend fun loadIdentitiesAndHost() {
        val db = App.get().db
        identities = db.identities().all()
        val labels = listOf(getString(R.string.label_no_identity)) + identities.map { it.name }
        b.dropdownIdentity.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        b.dropdownIdentity.setText(labels[0], false)

        if (editingId != 0L) {
            val host = db.hosts().byId(editingId) ?: return
            b.inputAlias.setText(host.alias)
            b.inputHostname.setText(host.hostname)
            b.inputPort.setText(host.port.toString())
            b.inputUsername.setText(host.username)
            b.inputPassword.setText(host.password ?: "")
            selectedIdentityId = host.identityId
            val idx = identities.indexOfFirst { it.id == host.identityId }
            if (idx >= 0) b.dropdownIdentity.setText(labels[idx + 1], false)
        }
    }

    private fun save() {
        val hostname = b.inputHostname.text.toString().trim()
        val username = b.inputUsername.text.toString().trim()
        val portStr = b.inputPort.text.toString().trim()
        if (hostname.isEmpty() || username.isEmpty() || portStr.isEmpty()) {
            Snackbar.make(b.root, "Hostname, port and username are required", Snackbar.LENGTH_LONG).show()
            return
        }
        val port = portStr.toIntOrNull() ?: 22
        val entity = HostEntity(
            id = editingId,
            alias = b.inputAlias.text.toString().trim(),
            hostname = hostname,
            port = port,
            username = username,
            password = b.inputPassword.text.toString().takeIf { it.isNotEmpty() },
            identityId = selectedIdentityId
        )
        lifecycleScope.launch {
            val dao = App.get().db.hosts()
            if (editingId == 0L) dao.insert(entity) else dao.update(entity)
            finish()
        }
    }

    private fun delete() {
        if (editingId == 0L) return
        lifecycleScope.launch {
            val dao = App.get().db.hosts()
            dao.byId(editingId)?.let { dao.delete(it) }
            finish()
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun editIntent(ctx: Context, id: Long) = Intent(ctx, HostEditorActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
