package com.example.termiusclone.ui.knownhosts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.termiusclone.App
import com.example.termiusclone.R
import com.example.termiusclone.data.db.KnownHostEntity
import com.example.termiusclone.databinding.ActivityKnownHostsBinding
import com.example.termiusclone.databinding.ItemKnownHostBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class KnownHostsActivity : AppCompatActivity() {

    private lateinit var b: ActivityKnownHostsBinding
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityKnownHostsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }
        adapter = Adapter { entity ->
            AlertDialog.Builder(this)
                .setTitle(R.string.known_hosts_remove_title)
                .setMessage(getString(R.string.known_hosts_remove_msg, "${entity.host}:${entity.port}"))
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    lifecycleScope.launch {
                        App.get().db.knownHosts().remove(entity.host, entity.port)
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        lifecycleScope.launch {
            App.get().db.knownHosts().observeAll().collectLatest { list ->
                adapter.submit(list)
                b.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private class Adapter(val onRemove: (KnownHostEntity) -> Unit) : RecyclerView.Adapter<Adapter.VH>() {
        private val items = mutableListOf<KnownHostEntity>()

        fun submit(list: List<KnownHostEntity>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = items.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(o: Int, n: Int) =
                    items[o].host == list[n].host && items[o].port == list[n].port
                override fun areContentsTheSame(o: Int, n: Int) = items[o] == list[n]
            })
            items.clear(); items.addAll(list); diff.dispatchUpdatesTo(this)
        }

        class VH(val binding: ItemKnownHostBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemKnownHostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.textHost.text = "${item.host}:${item.port}"
            holder.binding.textFingerprint.text = "${item.keyType}\n${item.fingerprint}"
            holder.binding.btnRemove.setOnClickListener { onRemove(item) }
        }
    }

    companion object {
        fun intent(ctx: Context) = Intent(ctx, KnownHostsActivity::class.java)
    }
}
