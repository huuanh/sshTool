package com.example.termiusclone.ui.hosts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.termiusclone.data.db.HostEntity
import com.example.termiusclone.databinding.ItemHostBinding

class HostsAdapter(
    private val onClick: (HostEntity) -> Unit,
    private val onConnect: (HostEntity) -> Unit,
    private val onFiles: (HostEntity) -> Unit
) : RecyclerView.Adapter<HostsAdapter.VH>() {

    private val items = mutableListOf<HostEntity>()

    fun submit(newItems: List<HostEntity>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition].id == newItems[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                items[oldItemPosition] == newItems[newItemPosition]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    class VH(val binding: ItemHostBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemHostBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val host = items[position]
        val alias = host.alias.ifBlank { host.hostname }
        holder.binding.textAlias.text = alias
        holder.binding.textTarget.text = "${host.username}@${host.hostname}:${host.port}"
        holder.binding.root.setOnClickListener { onClick(host) }
        holder.binding.btnConnect.setOnClickListener { onConnect(host) }
        holder.binding.btnFiles.setOnClickListener { onFiles(host) }
    }
}
