package org.soralis.droidsillica.ui.tab.view

import androidx.core.view.isVisible
import org.soralis.droidsillica.databinding.FragmentTabCloneBinding
import org.soralis.droidsillica.model.TabContent

class CloneView(
    private val binding: FragmentTabCloneBinding,
    private val onStartClone: () -> Unit
) : BaseTabView(binding.toTabUiComponents()) {

    init {
        binding.cloneStartButton.setOnClickListener {
            onStartClone()
        }
    }

    override fun render(content: TabContent) {
        super.render(content)
        binding.cloneStartButton.isEnabled = true
    }

    fun showWaiting() {
        binding.cloneResultText.text = "Waiting for card..."
        binding.cloneStartButton.isEnabled = false
    }

    fun showResult(result: String) {
        binding.cloneResultText.text = result
        binding.cloneStartButton.isEnabled = true
    }

    fun showError(message: String) {
        binding.cloneResultText.text = "Error: $message"
        binding.cloneStartButton.isEnabled = true
    }

    fun showNfcUnavailable() {
        binding.cloneResultText.text = "NFC unavailable on this device."
        binding.cloneStartButton.isEnabled = false
    }
}
