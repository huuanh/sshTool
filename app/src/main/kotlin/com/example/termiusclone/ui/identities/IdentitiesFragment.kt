package com.example.termiusclone.ui.identities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.termiusclone.App
import com.example.termiusclone.data.db.IdentityEntity
import com.example.termiusclone.databinding.FragmentIdentitiesBinding
import com.example.termiusclone.databinding.ItemIdentityBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class IdentitiesFragment : Fragment() {

    private var _b: FragmentIdentitiesBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: IdentityAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentIdentitiesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = IdentityAdapter { id ->
            startActivity(IdentityEditorActivity.editIntent(requireContext(), id))
        }
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), IdentityEditorActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            App.get().db.identities().observeAll().collectLatest { list ->
                adapter.submit(list)
                b.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

class IdentityAdapter(private val onClick: (Long) -> Unit) : RecyclerView.Adapter<IdentityAdapter.VH>() {
    private val items = mutableListOf<IdentityEntity>()

    fun submit(newItems: List<IdentityEntity>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o].id == newItems[n].id
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == newItems[n]
        })
        items.clear(); items.addAll(newItems); diff.dispatchUpdatesTo(this)
    }

    class VH(val binding: ItemIdentityBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemIdentityBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.textName.text = item.name
        val authType = if (!item.privateKey.isNullOrBlank()) "private key" else "password"
        holder.binding.textUsername.text = "${item.username} • $authType"
        holder.binding.root.setOnClickListener { onClick(item.id) }
    }
}
