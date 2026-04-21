package com.example.termiusclone.ui.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.example.termiusclone.App
import com.example.termiusclone.R
import com.example.termiusclone.backup.BackupManager
import com.example.termiusclone.databinding.FragmentSettingsBinding
import com.example.termiusclone.ui.knownhosts.KnownHostsActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { runExport(it) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { runImport(it) } }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("known_hosts")?.setOnPreferenceClickListener {
            startActivity(KnownHostsActivity.intent(requireContext())); true
        }
        findPreference<Preference>("export_backup")?.setOnPreferenceClickListener {
            exportLauncher.launch("termius-clone-backup-${System.currentTimeMillis()}.json"); true
        }
        findPreference<Preference>("import_backup")?.setOnPreferenceClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")); true
        }
    }

    private fun runExport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { BackupManager.export(App.get().db) }
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
                snack(getString(R.string.backup_exported))
            } catch (e: Throwable) {
                snack(getString(R.string.backup_failed, e.message ?: ""))
            }
        }
    }

    private fun runImport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: return@launch
                val (h, i, s) = withContext(Dispatchers.IO) { BackupManager.import(App.get().db, text) }
                snack(getString(R.string.backup_imported, h, i, s))
            } catch (e: Throwable) {
                snack(getString(R.string.backup_failed, e.message ?: ""))
            }
        }
    }

    private fun snack(msg: String) {
        view?.let { Snackbar.make(it, msg, Snackbar.LENGTH_LONG).show() }
    }
}
