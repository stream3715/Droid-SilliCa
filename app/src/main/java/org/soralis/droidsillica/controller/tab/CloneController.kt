package org.soralis.droidsillica.controller.tab

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Handler
import android.os.Looper
import org.soralis.droidsillica.model.RawExchange
import org.soralis.droidsillica.model.TabContent
import org.soralis.droidsillica.util.toLegacyHexString
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale

class CloneController {

    interface Listener {
        fun onWaitingForTag()
        fun onCloneSuccess(result: CloneResult)
        fun onCloneError(message: String)
        fun onCloningStopped()
        fun onNfcUnavailable()
    }

    data class CloneResult(
        val idm: ByteArray,
        val pmm: ByteArray,
        val blocks: Map<Int, ByteArray>
    ) {
        val formattedIdm: String = idm.toLegacyHexString()
        val formattedPmm: String = pmm.toLegacyHexString()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var nfcAdapter: NfcAdapter? = null
    private var readerModeEnabled = false
    private var activityRef: WeakReference<Activity>? = null
    private var sessionListener: Listener? = null

    fun getContent(): TabContent = TabContent(
        key = "clone",
        title = "Clone",
        description = "Read IDm/PMm (Sys 0x8AC3) and blocks from services 8000, 8007, 8008, 8009.",
        actions = listOf(
            "Tap a compatible card (System Code 0x8AC3).",
            "The app will read IDm, PMm, and specified service blocks."
        )
    )

    fun startCloning(activity: Activity, listener: Listener) {
        sessionListener = listener
        activityRef = WeakReference(activity)
        val adapter = nfcAdapter ?: NfcAdapter.getDefaultAdapter(activity).also { nfcAdapter = it }
        if (adapter == null) {
            listener.onNfcUnavailable()
            sessionListener = null
            return
        }
        adapter.enableReaderMode(
            activity,
            readerCallback,
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
        readerModeEnabled = true
        listener.onWaitingForTag()
    }

    fun stopCloning() {
        if (readerModeEnabled) {
            activityRef?.get()?.let { activity ->
                nfcAdapter?.disableReaderMode(activity)
            }
        }
        readerModeEnabled = false
        sessionListener?.onCloningStopped()
        sessionListener = null
    }

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        try {
            val nfcF = NfcF.get(tag) ?: throw IOException("Tag is not NfcF")
            nfcF.connect()
            nfcF.timeout = 1000

            // 1. Polling with System Code 0x8AC3
            val (idm, pmm) = polling(nfcF, SYSTEM_CODE_CLONE)

            // 2. Read Blocks from Services 8000, 8007, 8008, 8009
            // We assume Block 0 for each service.
            val services = listOf(0x8000, 0x8007, 0x8008, 0x8009)
            val blockDataMap = mutableMapOf<Int, ByteArray>()

            // We can read them in one command or multiple. 
            // To be safe and simple, let's try one command with multiple services.
            // Services: 0x8000(0), 0x8007(1), 0x8008(2), 0x8009(3)
            // BlockList: [0->0], [1->0], [2->0], [3->0]
            // (Access Mode 0, Service Code Index X, Block Number 0)
            
            // However, we need to construct the block list elements correctly.
            // 2-byte block list element:
            // Byte 0: 1 (Access Mode) << 7 | (Order & 0x0F) << 3 | ... 
            // Actually, for Read Without Encryption:
            // 2-byte element: 
            //   Bit 15: 0 (Long) / 1 (Short - 2 byte) - Wait, standard is 2 bytes or 3 bytes.
            //   Standard access is usually implied.
            //   If we use 2-byte block list element:
            //   Byte 0: Access Mode (1 bit) | Service Code Index (3 bits) | ...
            //   Wait, FeliCa structure is complex.
            //   Let's check ReadController's buildBlockList implementation.
            //   It uses 2-byte elements: `BLOCK_LIST_ACCESS_MODE` (0x80) followed by block number.
            //   0x80 means 2-byte element, Service Code Index 0.
            
            // We have multiple services here.
            // If we want to read from different services, we need to set the Service Code Index.
            // Byte 0 of Block List Element:
            //   Bit 7: 1 (2-byte format)
            //   Bits 6-4: Service Code List Order (Index)
            //   Bits 3-0: Access Mode (0000 = Read/Write w/o encryption?) - Actually Access Mode is usually 0.
            //   Wait, let's re-verify FeliCa Block List Element (2-byte).
            //   First byte:
            //     d7: 1 (Format: 2-byte)
            //     d6-d4: Service Code Index (0-7)
            //     d3: Access Mode (0)
            //     d2-d0: Service Code Index (if d7=0?)
            
            //   Actually:
            //   If d7=0 (3-byte element)
            //   If d7=1 (2-byte element)
            //      d6-d4: Service Code List Order (0-7)
            //      d3-d0: Block Number (High bits? No)
            //      Second byte: Block Number
            
            //   Wait, ReadController uses `0x80` followed by `sanitized`.
            //   0x80 = 1000 0000. d7=1. d6-d4=000 (Service Index 0).
            
            //   So if we have 4 services in the list:
            //   Service 0: 0x8000 -> Index 0 -> Header 0x80
            //   Service 1: 0x8007 -> Index 1 -> Header 0x90 (1001 0000)
            //   Service 2: 0x8008 -> Index 2 -> Header 0xA0 (1010 0000)
            //   Service 3: 0x8009 -> Index 3 -> Header 0xB0 (1011 0000)
            
            val blocks = mutableListOf<ByteArray>()
            
            // Build command
            val blockCount = services.size
            // 1 (len) + 1 (cmd) + 8 (idm) + 1 (num services) + 2*N (services) + 1 (num blocks) + 2*M (blocks)
            val cmdLen = 1 + 1 + 8 + 1 + (services.size * 2) + 1 + (blockCount * 2)
            val cmd = ByteArray(cmdLen)
            var idx = 0
            cmd[idx++] = cmdLen.toByte()
            cmd[idx++] = 0x06.toByte() // Read Without Encryption
            System.arraycopy(idm, 0, cmd, idx, 8)
            idx += 8
            cmd[idx++] = services.size.toByte()
            services.forEach { s ->
                cmd[idx++] = (s and 0xFF).toByte()
                cmd[idx++] = ((s shr 8) and 0xFF).toByte()
            }
            cmd[idx++] = blockCount.toByte()
            
            // Block List Elements
            // For each service, read Block 0
            for (i in services.indices) {
                // 2-byte element:
                // Byte 1: 1 (2-byte) | ServiceIndex (3 bits) | 0000
                // 0x80 + (i << 4)
                val header = 0x80 or (i shl 4)
                cmd[idx++] = header.toByte()
                cmd[idx++] = 0x00.toByte() // Block 0
            }
            
            val response = nfcF.transceive(cmd)
            
            // Parse response
            // 1 (len) + 1 (resp code) + 8 (idm) + 1 (status1) + 1 (status2) + 1 (num blocks) + 16*N (data)
            if (response.size < 13 || response[1] != 0x07.toByte()) {
                throw IOException("Read failed or invalid response")
            }
            
            val status1 = response[10]
            val status2 = response[11]
            if (status1.toInt() != 0) {
                throw IOException("Read error: Status $status1 $status2")
            }
            
            val rxBlockCount = response[12].toInt()
            if (rxBlockCount != blockCount) {
                 throw IOException("Unexpected block count: $rxBlockCount")
            }
            
            var dataOffset = 13
            for (i in services.indices) {
                val block = ByteArray(16)
                System.arraycopy(response, dataOffset, block, 0, 16)
                blockDataMap[services[i]] = block
                dataOffset += 16
            }

            val result = CloneResult(idm, pmm, blockDataMap)
            mainHandler.post { sessionListener?.onCloneSuccess(result) }

        } catch (e: Exception) {
            mainHandler.post { sessionListener?.onCloneError(e.message ?: "Unknown error") }
        } finally {
            try { tag.techList.forEach { if (it == "android.nfc.tech.NfcF") NfcF.get(tag)?.close() } } catch (_: Exception) {}
        }
    }

    private fun polling(nfcF: NfcF, systemCode: Int): Pair<ByteArray, ByteArray> {
        val cmd = ByteArray(6)
        cmd[0] = 6
        cmd[1] = 0x00 // Polling
        cmd[2] = (systemCode and 0xFF).toByte()
        cmd[3] = ((systemCode shr 8) and 0xFF).toByte()
        cmd[4] = 0x00 // Request Code
        cmd[5] = 0x00 // Time Slot
        
        val response = nfcF.transceive(cmd)
        if (response.size < 18 || response[1] != 0x01.toByte()) {
             throw IOException("Polling failed")
        }
        
        val idm = response.copyOfRange(2, 10)
        val pmm = response.copyOfRange(10, 18)
        return idm to pmm
    }

    companion object {
        private const val SYSTEM_CODE_CLONE = 0x8AC3
    }
}
