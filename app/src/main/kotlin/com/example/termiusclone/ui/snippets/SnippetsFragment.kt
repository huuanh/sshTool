package com.example.termiusclone.ui.snippets

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.termiusclone.App
import com.example.termiusclone.R
import com.example.termiusclone.data.db.SnippetEntity
import com.example.termiusclone.databinding.FragmentSnippetsBinding
import com.example.termiusclone.databinding.ItemSnippetBinding
import com.example.termiusclone.ui.terminal.TerminalActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SnippetsFragment : Fragment() {

    private var _b: FragmentSnippetsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: SnippetAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSnippetsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SnippetAdapter(
            onClick = { s -> startActivity(SnippetEditorActivity.editIntent(requireContext(), s.id)) },
            onLongClick = { s -> showRunDialog(s) }
        )
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), SnippetEditorActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            App.get().db.snippets().observeAll().collectLatest { list ->
                adapter.submit(list)
                b.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showRunDialog(snippet: SnippetEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val hosts = App.get().db.hosts().allOnce()
            if (hosts.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.snippet_no_hosts)
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
                return@launch
            }
            val labels = hosts.map { (if (it.alias.isNotBlank()) it.alias else it.hostname) + "  (${it.username}@${it.hostname})" }
                .toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.snippet_run_title, snippet.name))
                .setItems(labels) { _, which ->
                    val host = hosts[which]
                    startActivity(TerminalActivity.intentWithCommand(requireContext(), host.id, snippet.command))
                }
                .show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

class SnippetAdapter(
    private val onClick: (SnippetEntity) -> Unit,
    private val onLongClick: (SnippetEntity) -> Unit
) : RecyclerView.Adapter<SnippetAdapter.VH>() {

    private val items = mutableListOf<SnippetEntity>()

    fun submit(newItems: List<SnippetEntity>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o].id == newItems[n].id
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == newItems[n]
        })
        items.clear(); items.addAll(newItems); diff.dispatchUpdatesTo(this)
    }

    class VH(val binding: ItemSnippetBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSnippetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.textName.text = item.name
        holder.binding.textCommand.text = item.command.replace('\n', ' ')
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.root.setOnLongClickListener { onLongClick(item); true }
    }
}
