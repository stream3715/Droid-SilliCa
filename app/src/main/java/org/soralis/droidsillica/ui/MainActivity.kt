package org.soralis.droidsillica.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import org.soralis.droidsillica.R
import org.soralis.droidsillica.controller.TabController
import org.soralis.droidsillica.databinding.ActivityMainBinding
import org.soralis.droidsillica.ui.tab.TabPagerAdapter
import org.soralis.droidsillica.util.ExpertModeManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tabController = TabController()
    private var tabMediator: TabLayoutMediator? = null
    private var expertModeEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.topAppBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        setSupportActionBar(binding.topAppBar)
        expertModeEnabled = savedInstanceState?.getBoolean(STATE_EXPERT_MODE)
            ?: ExpertModeManager.isExpertModeEnabled(this)
        setupTabs()
    }

    private fun setupTabs() {
        tabMediator?.detach()
        val tabs = tabController.getTabs(expertModeEnabled)
        binding.viewPager.adapter = TabPagerAdapter(this, tabs, expertModeEnabled)
        tabMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabs[position].title
        }.also { it.attach() }
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_expert_mode -> {
                showExpertModeDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showExpertModeDialog() {
        val options = arrayOf(
            getString(R.string.settings_mode_basic),
            getString(R.string.settings_mode_expert)
        )
        val checkedItem = if (expertModeEnabled) 1 else 0
        var selectedIndex = checkedItem
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_expert_mode_title)
            .setSingleChoiceItems(options, checkedItem) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val enableExpert = selectedIndex == 1
                if (expertModeEnabled != enableExpert) {
                    expertModeEnabled = enableExpert
                    ExpertModeManager.setExpertModeEnabled(this, enableExpert)
                    setupTabs()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_EXPERT_MODE, expertModeEnabled)
    }

    companion object {
        private const val STATE_EXPERT_MODE = "state_expert_mode"
    }
}
