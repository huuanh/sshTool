package com.example.termiusclone.ui.snippets

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.termiusclone.App
import com.example.termiusclone.data.db.SnippetEntity
import com.example.termiusclone.databinding.ActivitySnippetEditorBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class SnippetEditorActivity : AppCompatActivity() {

    private lateinit var b: ActivitySnippetEditorBinding
    private var editingId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySnippetEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        editingId = intent.getLongExtra(EXTRA_ID, 0L)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnDelete.visibility = if (editingId != 0L) View.VISIBLE else View.GONE
        b.btnSave.setOnClickListener { save() }
        b.btnDelete.setOnClickListener { delete() }

        if (editingId != 0L) {
            lifecycleScope.launch {
                App.get().db.snippets().byId(editingId)?.let {
                    b.inputName.setText(it.name)
                    b.inputCommand.setText(it.command)
                }
            }
        }
    }

    private fun save() {
        val name = b.inputName.text.toString().trim()
        val cmd = b.inputCommand.text.toString()
        if (name.isEmpty() || cmd.isBlank()) {
            Snackbar.make(b.root, "Name and command are required", Snackbar.LENGTH_LONG).show()
            return
        }
        val entity = SnippetEntity(id = editingId, name = name, command = cmd)
        lifecycleScope.launch {
            val dao = App.get().db.snippets()
            if (editingId == 0L) dao.insert(entity) else dao.update(entity)
            finish()
        }
    }

    private fun delete() {
        if (editingId == 0L) return
        lifecycleScope.launch {
            val dao = App.get().db.snippets()
            dao.byId(editingId)?.let { dao.delete(it) }
            finish()
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun editIntent(ctx: Context, id: Long) =
            Intent(ctx, SnippetEditorActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
