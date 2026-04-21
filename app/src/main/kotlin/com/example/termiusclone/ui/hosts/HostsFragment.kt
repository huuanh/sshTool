package com.example.termiusclone.ui.hosts

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.termiusclone.App
import com.example.termiusclone.databinding.FragmentHostsBinding
import com.example.termiusclone.ui.hosteditor.HostEditorActivity
import com.example.termiusclone.ui.sftp.SftpActivity
import com.example.termiusclone.ui.terminal.TerminalActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HostsFragment : Fragment() {

    private var _b: FragmentHostsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: HostsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentHostsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = HostsAdapter(
            onClick = { host -> startActivity(HostEditorActivity.editIntent(requireContext(), host.id)) },
            onConnect = { host -> startActivity(TerminalActivity.intent(requireContext(), host.id)) },
            onFiles = { host -> startActivity(SftpActivity.intent(requireContext(), host.id)) }
        )
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), HostEditorActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            App.get().db.hosts().observeAll().collectLatest { list ->
                adapter.submit(list)
                b.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
