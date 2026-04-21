package com.example.termiusclone.ui.sftp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.termiusclone.R
import com.example.termiusclone.databinding.ItemSftpBinding
import com.example.termiusclone.ssh.SftpSession
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.xfer.FilePermission
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SftpAdapter(
    private val onClick: (RemoteResourceInfo) -> Unit,
    private val onLongClick: (RemoteResourceInfo) -> Unit
) : RecyclerView.Adapter<SftpAdapter.VH>() {

    private val items = mutableListOf<RemoteResourceInfo>()
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun submit(newItems: List<RemoteResourceInfo>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o].name == newItems[n].name
            override fun areContentsTheSame(o: Int, n: Int) =
                items[o].name == newItems[n].name &&
                items[o].attributes.size == newItems[n].attributes.size &&
                items[o].attributes.mtime == newItems[n].attributes.mtime
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun itemAt(pos: Int): RemoteResourceInfo? = items.getOrNull(pos)

    class VH(val b: ItemSftpBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemSftpBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val isDir = SftpSession.isDirectory(item)
        val isLink = SftpSession.isSymlink(item)
        holder.b.icon.setImageResource(if (isDir) R.drawable.ic_folder else R.drawable.ic_file)
        holder.b.name.text = if (isLink) "${item.name} →" else item.name
        val perms = permsToString(item.attributes.permissions, isDir)
        val size = if (isDir) "" else humanSize(item.attributes.size)
        val mtime = item.attributes.mtime.takeIf { it > 0 }
            ?.let { df.format(Date(it * 1000L)) } ?: ""
        holder.b.meta.text = listOf(perms, size, mtime).filter { it.isNotEmpty() }.joinToString("   ")
        holder.b.root.setOnClickListener { onClick(item) }
        holder.b.root.setOnLongClickListener { onLongClick(item); true }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var u = 0
        while (value >= 1024 && u < units.size - 1) { value /= 1024; u++ }
        return String.format(Locale.US, "%.1f %s", value, units[u])
    }

    @Suppress("MagicNumber")
    private fun permsToString(perms: Set<FilePermission>, isDir: Boolean): String {
        val sb = StringBuilder(10)
        sb.append(if (isDir) 'd' else '-')
        sb.append(if (FilePermission.USR_R in perms) 'r' else '-')
        sb.append(if (FilePermission.USR_W in perms) 'w' else '-')
        sb.append(if (FilePermission.USR_X in perms) 'x' else '-')
        sb.append(if (FilePermission.GRP_R in perms) 'r' else '-')
        sb.append(if (FilePermission.GRP_W in perms) 'w' else '-')
        sb.append(if (FilePermission.GRP_X in perms) 'x' else '-')
        sb.append(if (FilePermission.OTH_R in perms) 'r' else '-')
        sb.append(if (FilePermission.OTH_W in perms) 'w' else '-')
        sb.append(if (FilePermission.OTH_X in perms) 'x' else '-')
        return sb.toString()
    }
}
