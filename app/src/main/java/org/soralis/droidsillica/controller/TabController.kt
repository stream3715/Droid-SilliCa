package org.soralis.droidsillica.controller

import org.soralis.droidsillica.controller.tab.CloneController
import org.soralis.droidsillica.controller.tab.HistoryController
import org.soralis.droidsillica.controller.tab.ManualController
import org.soralis.droidsillica.controller.tab.ReadController
import org.soralis.droidsillica.controller.tab.WriteController
import org.soralis.droidsillica.model.TabContent

/**
 * Acts as the Controller by exposing immutable tab models to the view layer.
 */
class TabController {

    private val tabContents = listOf(
        ReadController().getContent(),
        CloneController().getContent(),
        WriteController().getContent(),
        ManualController().getContent(),
        HistoryController().getContent()
    )

    fun getTabs(isExpertMode: Boolean): List<TabContent> = tabContents.filter {
        !it.requiresExpert || isExpertMode
    }
}
