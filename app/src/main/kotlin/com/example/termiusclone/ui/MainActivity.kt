package com.example.termiusclone.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.termiusclone.R
import com.example.termiusclone.databinding.ActivityMainBinding
import com.example.termiusclone.ui.hosts.HostsFragment
import com.example.termiusclone.ui.identities.IdentitiesFragment
import com.example.termiusclone.ui.settings.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
                R.id.nav_identities -> IdentitiesFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> return@setOnItemSelectedListener false
            }
            switchTo(frag)
            true
        }
    }

    private fun switchTo(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, fragment)
            .commit()
    }
}
