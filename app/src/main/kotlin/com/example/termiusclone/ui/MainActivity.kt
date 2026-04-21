package com.example.termiusclone.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.termiusclone.R
import com.example.termiusclone.databinding.ActivityMainBinding
import com.example.termiusclone.security.AppLock
import com.example.termiusclone.ui.hosts.HostsFragment
import com.example.termiusclone.ui.identities.IdentitiesFragment
import com.example.termiusclone.ui.settings.SettingsFragment
import com.example.termiusclone.ui.snippets.SnippetsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            switchTo(HostsFragment())
            binding.bottomNav.selectedItemId = R.id.nav_hosts
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val frag: Fragment = when (item.itemId) {
                R.id.nav_hosts -> HostsFragment()
                R.id.nav_snippets -> SnippetsFragment()
                R.id.nav_identities -> IdentitiesFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> return@setOnItemSelectedListener false
            }
            switchTo(frag)
            true
        }

        if (AppLock.isEnabled(this) && AppLock.isAvailable(this)) {
            binding.root.visibility = View.INVISIBLE
            AppLock.prompt(
                this,
                onSuccess = {
                    unlocked = true
                    binding.root.visibility = View.VISIBLE
                },
                onFail = { finishAffinity() }
            )
        } else {
            unlocked = true
        }
    }

    private fun switchTo(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, fragment)
            .commit()
    }
}
