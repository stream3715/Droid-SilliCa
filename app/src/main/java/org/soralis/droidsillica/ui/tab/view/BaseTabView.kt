package org.soralis.droidsillica.ui.tab.view

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.soralis.droidsillica.databinding.FragmentTabCloneBinding
import org.soralis.droidsillica.databinding.FragmentTabHistoryBinding
import org.soralis.droidsillica.databinding.FragmentTabManualBinding
import org.soralis.droidsillica.databinding.FragmentTabReadBinding
import org.soralis.droidsillica.databinding.FragmentTabWriteBinding
import org.soralis.droidsillica.model.TabContent

open class BaseTabView(
    private val ui: TabUiComponents
) : TabView {

    override fun render(content: TabContent) {
        ui.tabTitle.text = content.title
        ui.tabDescription.text = content.description
        renderActions(content.actions)
    }

    protected fun renderActions(actions: List<String>) {
        val container = ui.actionList
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        actions.forEachIndexed { index, action ->
            val actionView =
                inflater.inflate(android.R.layout.simple_list_item_1, container, false) as TextView
            actionView.text = "${index + 1}. $action"
            container.addView(actionView)
        }
        container.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
    }
}

data class TabUiComponents(
    val tabTitle: TextView,
    val tabDescription: TextView,
    val actionList: LinearLayout
)

fun FragmentTabReadBinding.toTabUiComponents(): TabUiComponents =
    TabUiComponents(tabTitle, tabDescription, actionList)

fun FragmentTabWriteBinding.toTabUiComponents(): TabUiComponents =
    TabUiComponents(tabTitle, tabDescription, actionList)

fun FragmentTabManualBinding.toTabUiComponents(): TabUiComponents =
    TabUiComponents(tabTitle, tabDescription, actionList)

fun FragmentTabHistoryBinding.toTabUiComponents(): TabUiComponents =
    TabUiComponents(tabTitle, tabDescription, actionList)

fun FragmentTabCloneBinding.toTabUiComponents(): TabUiComponents =
    TabUiComponents(tabTitle, tabDescription, actionList)
