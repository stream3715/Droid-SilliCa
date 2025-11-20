package org.soralis.droidsillica.ui.tab

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import org.soralis.droidsillica.R
import org.soralis.droidsillica.controller.tab.CloneController
import org.soralis.droidsillica.controller.tab.HistoryController
import org.soralis.droidsillica.controller.tab.ReadController
import org.soralis.droidsillica.controller.tab.WriteController
import org.soralis.droidsillica.databinding.FragmentTabCloneBinding
import org.soralis.droidsillica.databinding.FragmentTabHistoryBinding
import org.soralis.droidsillica.databinding.FragmentTabManualBinding
import org.soralis.droidsillica.databinding.FragmentTabReadBinding
import org.soralis.droidsillica.databinding.FragmentTabWriteBinding
import org.soralis.droidsillica.model.TabContent
import org.soralis.droidsillica.model.RawExchange
import org.soralis.droidsillica.ui.tab.view.BaseTabView
import org.soralis.droidsillica.ui.tab.view.CloneView
import org.soralis.droidsillica.ui.tab.view.HistoryView
import org.soralis.droidsillica.ui.tab.view.ManualView
import org.soralis.droidsillica.ui.tab.view.ReadView
import org.soralis.droidsillica.ui.tab.view.TabView
import org.soralis.droidsillica.ui.tab.view.WriteView
import org.soralis.droidsillica.ui.tab.view.toTabUiComponents
import org.soralis.droidsillica.util.HistoryLogger
import org.soralis.droidsillica.util.SystemBlockHexCodec
import org.soralis.droidsillica.util.toLegacyHexString

class TabFragment : Fragment() {

    private var _readBinding: FragmentTabReadBinding? = null
    private var _writeBinding: FragmentTabWriteBinding? = null
    private var _manualBinding: FragmentTabManualBinding? = null
    private var _historyBinding: FragmentTabHistoryBinding? = null
    private var _cloneBinding: FragmentTabCloneBinding? = null
    private var tabView: TabView? = null
    private var readView: ReadView? = null
    private var writeView: WriteView? = null
    private var historyView: HistoryView? = null
    private var cloneView: CloneView? = null
    private var expertModeEnabled: Boolean = false
    private val readController = ReadController()
    private val writeController = WriteController()
    private val historyController = HistoryController()
    private val cloneController = CloneController()
    private var pendingReadRequest: ReadRequestMetadata? = null
    private var pendingWriteRequest: WriteController.WriteRequest? = null
    private var fullDumpState: FullDumpState? = null
    private var batchWriteState: BatchWriteState? = null
    private var lastSystemBlockTimestamp: Long? = null
    private var hasSystemBlockSnapshot: Boolean = false
    private var pendingExportTimestamp: Long? = null
    private var exportableHistoryTimestamps: Set<Long> = emptySet()

    private val exportHexLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            handleHexExportResult(uri)
        }

    private val importHexLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            handleHexImportResult(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val key = requireArguments().getString(ARG_KEY).orEmpty()
        return when (key) {
            KEY_READ -> FragmentTabReadBinding.inflate(inflater, container, false).also {
                _readBinding = it
            }.root
            KEY_WRITE -> FragmentTabWriteBinding.inflate(inflater, container, false).also {
                _writeBinding = it
            }.root
            KEY_MANUAL -> FragmentTabManualBinding.inflate(inflater, container, false).also {
                _manualBinding = it
            }.root
            KEY_HISTORY -> FragmentTabHistoryBinding.inflate(inflater, container, false).also {
                _historyBinding = it
            }.root
            KEY_CLONE -> FragmentTabCloneBinding.inflate(inflater, container, false).also {
                _cloneBinding = it
            }.root
            else -> FragmentTabWriteBinding.inflate(inflater, container, false).also {
                _writeBinding = it
            }.root
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        expertModeEnabled =
            savedInstanceState?.getBoolean(STATE_EXPERT_MODE)
                ?: args.getBoolean(ARG_EXPERT_MODE, false)
        val key = args.getString(ARG_KEY).orEmpty()
        val title = args.getString(ARG_TITLE).orEmpty()
        val description = args.getString(ARG_DESCRIPTION).orEmpty()
        val actions = args.getStringArray(ARG_ACTIONS)?.toList().orEmpty()

        val content = TabContent(
            key = key,
            title = title,
            description = description,
            actions = actions
        )

        tabView = createTabView(content.key)
        tabView?.render(content)
        when (content.key) {
            KEY_READ -> readView?.setExpertFeaturesVisible(expertModeEnabled)
            KEY_WRITE -> writeView?.setExpertMode(expertModeEnabled)
        }
        when (content.key) {
            KEY_HISTORY -> refreshHistory()
            KEY_READ -> {
                refreshSystemBlockExportAvailability()
                updateFullDumpControls()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
        if (arguments?.getString(ARG_KEY) == KEY_READ) {
            refreshSystemBlockExportAvailability()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tabView = null
        _readBinding = null
        _writeBinding = null
        _manualBinding = null
        _historyBinding = null
        _cloneBinding = null
        readController.stopReading()
        readView = null
        writeController.stopWriting()
        writeView = null
        historyView = null
        cloneController.stopCloning()
        cloneView = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_EXPERT_MODE, expertModeEnabled)
    }

    private fun createTabView(key: String): TabView {
        if (key != KEY_READ) {
            readView = null
        }
        if (key != KEY_WRITE) {
            writeView = null
        }
        if (key != KEY_HISTORY) {
            historyView = null
        }
        if (key != KEY_CLONE) {
            cloneView = null
        }
        return when (key) {
            KEY_READ -> ReadView(
                _readBinding ?: error("Missing read binding"),
                readCallbacks
            ).also { readView = it }
            KEY_WRITE -> WriteView(
                _writeBinding ?: error("Missing write binding"),
                writeCallbacks
            ).also { writeView = it }
            KEY_MANUAL -> ManualView(_manualBinding ?: error("Missing manual binding"))
            KEY_HISTORY -> HistoryView(
                _historyBinding ?: error("Missing history binding"),
                historyCallbacks
            ).also {
                historyView = it
            }
            KEY_CLONE -> CloneView(
                _cloneBinding ?: error("Missing clone binding"),
                onStartClone = {
                    activity?.let { cloneController.startCloning(it, cloneListener) }
                }
            ).also { cloneView = it }
            else -> BaseTabView(
                _writeBinding?.toTabUiComponents()
                    ?: _manualBinding?.toTabUiComponents()
                    ?: _historyBinding?.toTabUiComponents()
                    ?: _readBinding?.toTabUiComponents()
                    ?: _cloneBinding?.toTabUiComponents()
                    ?: error("No binding available for $key tab")
            )
        }
    }

    private val cloneListener = object : CloneController.Listener {
        override fun onWaitingForTag() {
            cloneView?.showWaiting()
        }

        override fun onCloneSuccess(result: CloneController.CloneResult) {
            val sb = StringBuilder()
            sb.append("IDm: ${result.formattedIdm}\n")
            sb.append("PMm: ${result.formattedPmm}\n\n")
            result.blocks.forEach { (serviceCode, data) ->
                sb.append("Service ${String.format("%04X", serviceCode)}: ${data.toLegacyHexString()}\n")
            }
            cloneView?.showResult(sb.toString())
        }

        override fun onCloneError(message: String) {
            cloneView?.showError(message)
        }

        override fun onCloningStopped() {
            // cloneView?.showResult("Cloning stopped.")
        }

        override fun onNfcUnavailable() {
            cloneView?.showNfcUnavailable()
        }
    }

    private val readCallbacks = object : ReadView.Callbacks {
        override fun onStartReading(blockNumbers: List<Int>, readLastErrorCommand: Boolean) {
            val activity = activity ?: return
            pendingReadRequest = ReadRequestMetadata(
                blockNumbers.toList(),
                readLastErrorCommand,
                ReadRequestType.STANDARD
            )
            readController.startReading(activity, blockNumbers, readLastErrorCommand, readListener)
        }

        override fun onStopReading() {
            readController.stopReading()
        }

        override fun onExportSystemBlocks() {
            if (!expertModeEnabled) {
                showToast(getString(R.string.expert_mode_required))
                return
            }
            val timestamp = lastSystemBlockTimestamp
            if (timestamp == null) {
                showToast(getString(R.string.read_export_hex_none))
                return
            }
            pendingExportTimestamp = timestamp
            val defaultName = "silica_$timestamp.hex"
            exportHexLauncher.launch(defaultName)
        }

        override fun onFullDumpRequested() {
            startFullDumpRead()
        }

        override fun onFullDumpReset() {
            resetFullDumpState()
        }
    }

    private val readListener = object : ReadController.Listener {
        override fun onWaitingForTag() {
            readView?.showResultMessage(getString(R.string.read_result_waiting_for_tag))
            readView?.showRawLog(getString(R.string.read_raw_log_placeholder))
        }

        override fun onReadSuccess(result: ReadController.ReadResult) {
            val request = pendingReadRequest
            val displayResult = if (request?.type == ReadRequestType.FULL_DUMP) {
                handleFullDumpSuccess(result) ?: run {
                    pendingReadRequest = null
                    return
                }
            } else {
                result
            }
            val blockSummary = formatBlockSummary(displayResult)
            val rawLogText = formatRawLog(displayResult.rawExchanges)
            val systemCodesText = formatCodeList(
                displayResult.systemCodes,
                getString(R.string.read_result_no_system_codes)
            )
            val serviceCodesText = formatCodeList(
                displayResult.serviceCodes,
                getString(R.string.read_result_no_service_codes)
            )
            val resultMessage = getString(
                R.string.read_result_success,
                displayResult.formattedIdm,
                displayResult.formattedPmm,
                systemCodesText,
                serviceCodesText,
                blockSummary
            )
            readView?.showResultMessage(resultMessage)
            readView?.showRawLog(rawLogText)
            readView?.setReadingInProgress(false)
            val appContext = context?.applicationContext
            var historyTimestamp: Long? = null
            if (appContext != null) {
                historyTimestamp = HistoryLogger.logReadSuccess(
                    appContext,
                    displayResult,
                    request?.blockNumbers,
                    request?.readBlocks ?: true,
                    resultMessage,
                    rawLogText
                )
            }
            refreshHistory()
            if (displayResult.blockData.isNotEmpty() && displayResult.blockNumbers.isNotEmpty()) {
                if (historyTimestamp != null) {
                    lastSystemBlockTimestamp = historyTimestamp
                    hasSystemBlockSnapshot = true
                    readView?.setExportEnabled(true)
                } else {
                    refreshSystemBlockExportAvailability()
                }
            } else {
                readView?.setExportEnabled(hasSystemBlockSnapshot)
            }
            pendingReadRequest = null
        }

        override fun onReadError(
            message: String,
            rawLog: List<RawExchange>,
            partialResult: ReadController.PartialReadResult?
        ) {
            val request = pendingReadRequest
            val baseMessage = getString(R.string.read_result_error, message)
            val detailMessage = when (request?.type) {
                ReadRequestType.FULL_DUMP -> handleFullDumpError(partialResult)
                else -> partialResult?.let { formatPartialReadResult(it) }
            }
            val resultMessage = if (detailMessage.isNullOrBlank()) {
                baseMessage
            } else {
                "$baseMessage\n\n$detailMessage"
            }
            readView?.showResultMessage(resultMessage)
            val rawLogText = if (rawLog.isNotEmpty()) {
                formatRawLog(rawLog)
            } else {
                getString(R.string.read_raw_log_placeholder)
            }
            readView?.showRawLog(rawLogText)
            readView?.setReadingInProgress(false)
            readView?.setExportEnabled(hasSystemBlockSnapshot)
            if (request?.type != ReadRequestType.FULL_DUMP) {
                val appContext = context?.applicationContext
                if (appContext != null) {
                    HistoryLogger.logReadError(
                        appContext,
                        message,
                        rawLog,
                        request?.blockNumbers,
                        request?.readBlocks ?: true,
                        resultMessage,
                        rawLogText
                    )
                }
            }
            refreshHistory()
            pendingReadRequest = null
        }

        override fun onReadingStopped() {
            val resultMessage = getString(R.string.read_result_stopped)
            val rawLogPlaceholder = getString(R.string.read_raw_log_placeholder)
            readView?.showResultMessage(resultMessage)
            readView?.showRawLog(rawLogPlaceholder)
            readView?.setReadingInProgress(false)
            readView?.setExportEnabled(hasSystemBlockSnapshot)
            val request = pendingReadRequest
            if (request != null && request.type != ReadRequestType.FULL_DUMP) {
                val appContext = context?.applicationContext
                if (appContext != null) {
                    HistoryLogger.logReadCancelled(
                        appContext,
                        request.blockNumbers,
                        request.readBlocks,
                        resultMessage,
                        rawLogPlaceholder
                    )
                }
            }
            refreshHistory()
            pendingReadRequest = null
        }

        override fun onNfcUnavailable() {
            val resultMessage = getString(R.string.read_result_no_nfc)
            val rawLogPlaceholder = getString(R.string.read_raw_log_placeholder)
            readView?.showResultMessage(resultMessage)
            readView?.showRawLog(rawLogPlaceholder)
            readView?.setReadingInProgress(false)
            readView?.setExportEnabled(hasSystemBlockSnapshot)
            val request = pendingReadRequest
            if (request?.type != ReadRequestType.FULL_DUMP) {
                val appContext = context?.applicationContext
                if (appContext != null) {
                    HistoryLogger.logReadError(
                        appContext,
                        getString(R.string.read_result_no_nfc),
                        emptyList(),
                        request?.blockNumbers,
                        request?.readBlocks ?: true,
                        resultMessage,
                        rawLogPlaceholder
                    )
                }
            }
            refreshHistory()
            pendingReadRequest = null
        }
    }

    private val writeCallbacks = object : WriteView.Callbacks {
        override fun onStartWriting(request: WriteController.WriteRequest) {
            val activity = activity ?: return
            pendingWriteRequest = request
            batchWriteState = if (request is WriteController.WriteRequest.RawBlockBatch) {
                BatchWriteState(request.blocks.size)
            } else {
                null
            }
            writeController.startWriting(activity, request, writeListener)
        }

        override fun onCancelWriting() {
            writeController.stopWriting()
        }

        override fun onWriteHexFileRequested() {
            if (!expertModeEnabled) {
                showToast(getString(R.string.expert_mode_required))
                return
            }
            importHexLauncher.launch(arrayOf("application/octet-stream", "text/plain", "text/*"))
        }
    }

    private val writeListener = object : WriteController.Listener {
        override fun onWaitingForTag() {
            writeView?.showResultMessage(getString(R.string.write_result_waiting))
            writeView?.showRawLog(getString(R.string.write_raw_log_placeholder))
        }

        override fun onWriteSuccess(result: WriteController.WriteResult) {
            val rawLogText = formatRawLog(result.rawExchanges)
            val resultMessage = getString(R.string.write_result_success)
            writeView?.showResultMessage(resultMessage)
            writeView?.showRawLog(rawLogText)
            writeView?.setWritingInProgress(false)
            val appContext = context?.applicationContext
            if (appContext != null) {
                HistoryLogger.logWriteSuccess(
                    appContext,
                    pendingWriteRequest,
                    result.rawExchanges,
                    resultMessage,
                    rawLogText
                )
            }
            refreshHistory()
            batchWriteState = null
            pendingWriteRequest = null
        }

        override fun onWriteError(
            message: String,
            rawLog: List<RawExchange>,
            completedPayloads: Int
        ) {
            val currentRequest = pendingWriteRequest
            val batchResult = handleBatchWriteError(currentRequest, completedPayloads)
            val progressMessage = batchResult?.progressMessage
            val baseMessage = getString(R.string.write_result_error, message)
            val resultMessage = if (progressMessage.isNullOrBlank()) {
                baseMessage
            } else {
                "$progressMessage\n$baseMessage"
            }
            writeView?.showResultMessage(resultMessage)
            val rawLogText = if (rawLog.isNotEmpty()) {
                formatRawLog(rawLog)
            } else {
                getString(R.string.write_raw_log_placeholder)
            }
            writeView?.showRawLog(rawLogText)
            if (batchResult?.resumed == true) {
                writeView?.setWritingInProgress(true)
            } else {
                writeView?.setWritingInProgress(false)
            }
            if (batchResult?.resumed != true) {
                val appContext = context?.applicationContext
                if (appContext != null) {
                    HistoryLogger.logWriteError(
                        appContext,
                        message,
                        currentRequest,
                        rawLog,
                        resultMessage,
                        rawLogText
                    )
                }
            }
            refreshHistory()
            if (batchResult?.resumed != true) {
                pendingWriteRequest = null
            }
        }

        override fun onWriteStopped() {
            val resultMessage = getString(R.string.write_result_cancelled)
            val rawPlaceholder = getString(R.string.write_raw_log_placeholder)
            writeView?.showResultMessage(resultMessage)
            writeView?.showRawLog(rawPlaceholder)
            writeView?.setWritingInProgress(false)
            batchWriteState = null
            val request = pendingWriteRequest
            if (request != null) {
                val appContext = context?.applicationContext
                if (appContext != null) {
                    HistoryLogger.logWriteCancelled(
                        appContext,
                        request,
                        resultMessage,
                        rawPlaceholder
                    )
                }
            }
            refreshHistory()
            pendingWriteRequest = null
        }

        override fun onNfcUnavailable() {
            val resultMessage = getString(R.string.write_result_no_nfc)
            val rawPlaceholder = getString(R.string.write_raw_log_placeholder)
            writeView?.showResultMessage(resultMessage)
            writeView?.showRawLog(rawPlaceholder)
            writeView?.setWritingInProgress(false)
            batchWriteState = null
            val appContext = context?.applicationContext
            if (appContext != null) {
                HistoryLogger.logWriteError(
                    appContext,
                    getString(R.string.write_result_no_nfc),
                    pendingWriteRequest,
                    emptyList(),
                    resultMessage,
                    rawPlaceholder
                )
            }
            refreshHistory()
            pendingWriteRequest = null
        }
    }

    private fun formatCodeList(codes: List<Int>, emptyText: String): String {
        if (codes.isEmpty()) return emptyText
        return codes.joinToString(", ") { code ->
            String.format(Locale.US, "0x%04X", code)
        }
    }

    private fun formatRawLog(exchanges: List<RawExchange>): String {
        if (exchanges.isEmpty()) {
            return getString(R.string.raw_data_unavailable)
        }
        return exchanges.joinToString(separator = "\n\n") { exchange ->
            getString(
                R.string.raw_log_entry,
                exchange.label,
                exchange.formattedRequest.ifEmpty { getString(R.string.raw_data_unavailable) },
                exchange.formattedResponse.ifEmpty { getString(R.string.raw_data_unavailable) }
            )
        }
    }

    private fun formatPartialReadResult(partial: ReadController.PartialReadResult): String {
        val systemCodesText = formatCodeList(
            partial.systemCodes,
            getString(R.string.read_result_no_system_codes)
        )
        val serviceCodesText = formatCodeList(
            partial.serviceCodes,
            getString(R.string.read_result_no_service_codes)
        )
        val blockSummary = if (partial.blockNumbers.isEmpty()) {
            getString(R.string.read_result_no_blocks)
        } else {
            getString(R.string.read_result_no_block_payload)
        }
        return getString(
            R.string.read_result_success,
            partial.formattedIdm,
            partial.formattedPmm,
            systemCodesText,
            serviceCodesText,
            blockSummary
        )
    }

    private fun formatBlockSummary(result: ReadController.ReadResult): String {
        if (result.lastErrorCommand.isNotEmpty()) {
            val commandText = result.formattedCommand.ifEmpty {
                getString(R.string.raw_data_unavailable)
            }
            return getString(R.string.read_result_last_error_command, commandText)
        }
        if (result.blockNumbers.isEmpty() || result.blockData.isEmpty()) {
            return getString(R.string.read_result_no_blocks)
        }
        val blockLines = result.blockNumbers.mapIndexedNotNull { index, blockNumber ->
            val start = index * FELICA_BLOCK_SIZE
            val end = start + FELICA_BLOCK_SIZE
            if (start < 0 || end > result.blockData.size) {
                null
            } else {
                val payload = result.blockData.copyOfRange(start, end).toLegacyHexString()
                getString(R.string.read_result_block_entry, blockNumber, payload)
            }
        }
        if (blockLines.isEmpty()) {
            return getString(R.string.read_result_no_block_payload)
        }
        return getString(R.string.read_result_blocks, blockLines.joinToString("\n"))
    }

    companion object {
        private const val ARG_KEY = "arg_key"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_DESCRIPTION = "arg_description"
        private const val ARG_ACTIONS = "arg_actions"
        private const val ARG_EXPERT_MODE = "arg_expert_mode"
        private const val STATE_EXPERT_MODE = "state_expert_mode"
        private const val KEY_READ = "read"
        private const val KEY_WRITE = "write"
        private const val KEY_MANUAL = "manual"
        private const val KEY_HISTORY = "history"
        private const val KEY_CLONE = "clone"
        private const val FELICA_BLOCK_SIZE = 16
        private const val FULL_DUMP_BLOCK_COUNT = 0xFF
        private val FULL_DUMP_BLOCKS = (0 until FULL_DUMP_BLOCK_COUNT).toList()

        fun newInstance(content: TabContent, expertModeEnabled: Boolean): TabFragment = TabFragment().apply {
            arguments = bundleOf(
                ARG_KEY to content.key,
                ARG_TITLE to content.title,
                ARG_DESCRIPTION to content.description,
                ARG_ACTIONS to content.actions.toTypedArray(),
                ARG_EXPERT_MODE to expertModeEnabled
            )
        }
    }

    private val historyCallbacks = object : HistoryView.Callbacks {
        override fun onDeleteEntry(timestamp: Long) {
            val context = context ?: return
            historyController.deleteHistoryEntry(context, timestamp)
            refreshHistory()
        }

        override fun onClearHistory() {
            val context = context ?: return
            historyController.clearHistory(context)
            refreshHistory()
        }

        override fun onExportEntry(timestamp: Long) {
            if (!expertModeEnabled) {
                showToast(getString(R.string.expert_mode_required))
                return
            }
            if (!exportableHistoryTimestamps.contains(timestamp)) {
                showToast(getString(R.string.read_export_hex_error))
                refreshHistory()
                return
            }
            pendingExportTimestamp = timestamp
            val defaultName = "silica_$timestamp.hex"
            exportHexLauncher.launch(defaultName)
        }
    }

    private fun refreshHistory() {
        if (arguments?.getString(ARG_KEY) != KEY_HISTORY) return
        val historyTabView = historyView ?: return
        val context = context ?: return
        val entries = historyController.getHistory(context)
        val exportable = historyController.getSystemBlockTimestamps(context)
        exportableHistoryTimestamps = exportable
        historyTabView.renderHistory(entries, exportable, expertModeEnabled)
    }

    private fun refreshSystemBlockExportAvailability() {
        val context = context ?: return
        val latest = HistoryLogger.getLatestSystemBlockEntry(context)
        lastSystemBlockTimestamp = latest?.timestamp
        hasSystemBlockSnapshot = latest != null
        readView?.setExportEnabled(hasSystemBlockSnapshot)
    }

    private fun handleHexExportResult(uri: Uri?) {
        if (!expertModeEnabled) {
            showToast(getString(R.string.expert_mode_required))
            return
        }
        val timestamp = pendingExportTimestamp
        pendingExportTimestamp = null
        if (uri == null || timestamp == null) {
            showToast(getString(R.string.read_export_hex_cancelled))
            return
        }
        val context = context ?: return
        val entry = HistoryLogger.getSystemBlockEntry(context, timestamp)
        if (entry == null) {
            showToast(getString(R.string.read_export_hex_error))
            refreshSystemBlockExportAvailability()
            return
        }
        val success = HistoryLogger.exportSystemBlockEntry(context, entry, uri)
        if (success) {
            showToast(
                getString(
                    R.string.read_export_hex_success,
                    uri.lastPathSegment ?: "system_blocks.hex"
                )
            )
        } else {
            showToast(getString(R.string.read_export_hex_error))
        }
    }

    private fun startFullDumpRead() {
        val activity = activity ?: return
        val state = fullDumpState ?: FullDumpState(FULL_DUMP_BLOCKS).also { fullDumpState = it }
        val remainingBlocks = state.remainingBlocks()
        if (remainingBlocks.isEmpty()) {
            readView?.showResultMessage(getString(R.string.read_result_full_dump_complete))
            updateFullDumpControls(state)
            return
        }
        pendingReadRequest = ReadRequestMetadata(
            remainingBlocks,
            true,
            ReadRequestType.FULL_DUMP
        )
        val progressMessage = getString(
            R.string.read_result_full_dump_progress,
            state.completedBlocks,
            state.blockNumbers.size
        )
        readView?.showResultMessage(progressMessage)
        readView?.showRawLog(getString(R.string.read_raw_log_placeholder))
        readView?.setReadingInProgress(true)
        readController.startReading(activity, remainingBlocks, true, readListener)
    }

    private fun resetFullDumpState() {
        fullDumpState = null
        updateFullDumpControls()
    }

    private fun handleFullDumpSuccess(result: ReadController.ReadResult): ReadController.ReadResult? {
        val state = fullDumpState ?: FullDumpState(FULL_DUMP_BLOCKS).also { fullDumpState = it }
        state.appendResult(result)
        return if (state.isComplete()) {
            val payload = state.buildBlockData()
            val aggregated = result.copy(
                blockNumbers = state.blockNumbers,
                blockData = payload
            )
            resetFullDumpState()
            aggregated
        } else {
            updateFullDumpControls(state)
            val progressText = getString(
                R.string.read_result_full_dump_progress,
                state.completedBlocks,
                state.blockNumbers.size
            )
            readView?.showResultMessage(progressText)
            readView?.showRawLog(formatRawLog(result.rawExchanges))
            readView?.setReadingInProgress(false)
            null
        }
    }

    private fun handleFullDumpError(partialResult: ReadController.PartialReadResult?): String? {
        val state = fullDumpState ?: FullDumpState(FULL_DUMP_BLOCKS).also { fullDumpState = it }
        if (partialResult != null && partialResult.blockData.isNotEmpty()) {
            state.appendPartial(partialResult)
        }
        updateFullDumpControls(state)
        return getString(
            R.string.read_result_full_dump_progress,
            state.completedBlocks,
            state.blockNumbers.size
        )
    }

    private fun updateFullDumpControls(state: FullDumpState? = fullDumpState) {
        val view = readView ?: return
        val activeState = state
        if (activeState == null || activeState.completedBlocks == 0) {
            view.setFullDumpButtonText(R.string.read_action_full_dump)
            view.setFullDumpResetEnabled(false)
        } else {
            view.setFullDumpButtonText(
                R.string.read_action_full_dump_resume,
                activeState.completedBlocks,
                activeState.blockNumbers.size
            )
            view.setFullDumpResetEnabled(true)
        }
        view.setFullDumpButtonEnabled(true)
    }

    private data class BatchWriteErrorState(val progressMessage: String, val resumed: Boolean)

    private fun handleBatchWriteError(
        request: WriteController.WriteRequest?,
        completedPayloads: Int
    ): BatchWriteErrorState? {
        val state = batchWriteState ?: return null
        if (request !is WriteController.WriteRequest.RawBlockBatch) {
            batchWriteState = null
            return null
        }
        val safeCompleted = completedPayloads.coerceIn(0, request.blocks.size)
        if (safeCompleted > 0) {
            state.completedBlocks = (state.completedBlocks + safeCompleted).coerceAtMost(state.totalBlocks)
        }
        val remainingBlocks = request.blocks.drop(safeCompleted)
        val progressMessage = getString(
            R.string.write_result_batch_progress,
            state.completedBlocks,
            state.totalBlocks
        )
        if (remainingBlocks.isEmpty()) {
            batchWriteState = null
            pendingWriteRequest = null
            return BatchWriteErrorState(progressMessage, resumed = false)
        }
        pendingWriteRequest = WriteController.WriteRequest.RawBlockBatch(remainingBlocks)
        val activity = activity
        return if (activity != null) {
            writeController.startWriting(activity, pendingWriteRequest!!, writeListener)
            BatchWriteErrorState(progressMessage, resumed = true)
        } else {
            BatchWriteErrorState(progressMessage, resumed = false)
        }
    }

    private fun handleHexImportResult(uri: Uri?) {
        if (!expertModeEnabled) {
            showToast(getString(R.string.expert_mode_required))
            return
        }
        if (uri == null) {
            showToast(getString(R.string.write_import_hex_cancelled))
            return
        }
        val context = context ?: return
        val raw = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.readText()
            }
        } catch (_: IOException) {
            null
        }
        if (raw.isNullOrBlank()) {
            showToast(getString(R.string.write_import_hex_invalid))
            return
        }
        val payload = SystemBlockHexCodec.decode(raw)
        if (payload == null || payload.blocks.isEmpty()) {
            showToast(getString(R.string.write_import_hex_invalid))
            return
        }
        startHexFileWrite(uri, payload)
    }

    private fun startHexFileWrite(uri: Uri, payload: SystemBlockHexCodec.HexPayload) {
        if (!expertModeEnabled) {
            showToast(getString(R.string.expert_mode_required))
            return
        }
        val activity = activity ?: return
        val blocks = payload.blocks.map {
            WriteController.WriteRequest.RawBlockPayload(it.blockNumber, it.data)
        }
        if (blocks.isEmpty()) {
            showToast(getString(R.string.write_import_hex_invalid))
            return
        }
        val request = WriteController.WriteRequest.RawBlockBatch(blocks)
        pendingWriteRequest = request
        batchWriteState = BatchWriteState(blocks.size)
        writeView?.showResultMessage(
            getString(
                R.string.write_import_hex_ready,
                blocks.size,
                getDisplayNameForUri(uri)
            )
        )
        writeView?.showRawLog(getString(R.string.write_raw_log_placeholder))
        writeView?.setWritingInProgress(true)
        writeController.startWriting(activity, request, writeListener)
    }

    private fun getDisplayNameForUri(uri: Uri): String {
        val context = context ?: return uri.lastPathSegment ?: "system_blocks.hex"
        var displayName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) {
                displayName = cursor.getString(index)
            }
        }
        return displayName ?: uri.lastPathSegment ?: "system_blocks.hex"
    }

    private fun showToast(message: String) {
        val context = context ?: return
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }


    private class FullDumpState(
        val blockNumbers: List<Int>
    ) {
        private val buffer = ByteArrayOutputStream()
        var completedBlocks: Int = 0
            private set

        fun remainingBlocks(): List<Int> = blockNumbers.drop(completedBlocks)

        fun appendResult(result: ReadController.ReadResult) {
            buffer.write(result.blockData)
            completedBlocks += result.blockNumbers.size
        }

        fun appendPartial(partial: ReadController.PartialReadResult) {
            buffer.write(partial.blockData)
            completedBlocks += partial.blockData.size / FELICA_BLOCK_SIZE
        }

        fun isComplete(): Boolean = completedBlocks >= blockNumbers.size

        fun buildBlockData(): ByteArray = buffer.toByteArray()
    }

    private data class BatchWriteState(
        val totalBlocks: Int,
        var completedBlocks: Int = 0
    )

    private enum class ReadRequestType {
        STANDARD,
        FULL_DUMP
    }

    private data class ReadRequestMetadata(
        val blockNumbers: List<Int>,
        val readBlocks: Boolean,
        val type: ReadRequestType
    )
}
