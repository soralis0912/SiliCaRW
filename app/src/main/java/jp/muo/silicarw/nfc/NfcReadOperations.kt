package jp.muo.silicarw.nfc

import android.nfc.tech.NfcF
import android.util.Log
import jp.muo.silicarw.util.toHexString
import kotlin.math.min

object NfcReadOperations {
    private const val COMMAND_READ = 0x06.toByte()
    private const val TAG = "NfcReadOps"

    fun fetchLastErrorCommand(nfcF: NfcF, currentIdm: ByteArray): String? {
        val blockList = byteArrayOf(
            0x80.toByte(), 0xE0.toByte(),
            0x80.toByte(), 0xE1.toByte()
        )
        val blockData = readSystemBlocks(nfcF, currentIdm, blockList) ?: return null
        if (blockData.isEmpty()) return null

        val length = blockData[0].toInt() and 0xFF
        val available = blockData.size - 1
        if (available <= 0) {
            return "なし"
        }
        val actualLength = min(length, available)
        if (actualLength <= 0) {
            return "なし"
        }
        val bytes = blockData.copyOfRange(1, 1 + actualLength)
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    fun readCodeListBlock(
        nfcF: NfcF,
        currentIdm: ByteArray,
        blockNumber: Int,
        swapBytes: Boolean
    ): List<String>? {
        val blockData = readSingleSystemBlock(nfcF, currentIdm, blockNumber) ?: return null
        if (blockData.isEmpty()) {
            return emptyList()
        }

        val codes = mutableListOf<String>()
        var index = 0
        while (index + 1 < blockData.size) {
            val first = blockData[index]
            val second = blockData[index + 1]
            if (first == 0.toByte() && second == 0.toByte()) {
                break
            }
            val pair = if (swapBytes) {
                byteArrayOf(second, first)
            } else {
                byteArrayOf(first, second)
            }
            codes += pair.toHexString()
            index += 2
        }
        return codes
    }

    fun readIdmPmm(
        nfcF: NfcF,
        currentIdm: ByteArray
    ): Pair<String, String>? {
        val blockData = readSingleSystemBlock(nfcF, currentIdm, 0x83) ?: return null
        if (blockData.size < 16) {
            return null
        }
        val idm = blockData.copyOfRange(0, 8).toHexString()
        val pmm = blockData.copyOfRange(8, 16).toHexString()
        return idm to pmm
    }

    fun formatCodeListDisplay(codes: List<String>?): String {
        return when {
            codes == null -> "読み取り失敗"
            codes.isEmpty() -> "なし"
            else -> codes.joinToString(", ")
        }
    }

    fun readCustomBlockDisplay(
        nfcF: NfcF,
        currentIdm: ByteArray,
        input: String
    ): String {
        val blockNumber = input.toIntOrNull()
        if (blockNumber == null || blockNumber !in 0..0xFF) {
            return "Block: 無効な番号"
        }
        val label = "Block %02X".format(blockNumber)
        val data = readSingleSystemBlock(nfcF, currentIdm, blockNumber)
        return if (data != null) {
            "$label: ${data.toHexString()}"
        } else {
            "$label: 読み取り失敗"
        }
    }

    private fun readSingleSystemBlock(
        nfcF: NfcF,
        currentIdm: ByteArray,
        blockNumber: Int
    ): ByteArray? {
        if (blockNumber !in 0..0xFF) {
            return null
        }
        val blockList = byteArrayOf(0x80.toByte(), blockNumber.toByte())
        return readSystemBlocks(nfcF, currentIdm, blockList)
    }

    fun readSystemBlocks(
        nfcF: NfcF,
        currentIdm: ByteArray,
        blockList: ByteArray
    ): ByteArray? {
        if (blockList.isEmpty() || blockList.size % 2 != 0) {
            Log.e(TAG, "Invalid block list for read: ${blockList.toHexString()}")
            return null
        }

        val blockCount = blockList.size / 2
        val cmdData = byteArrayOf(
            0x01,
            0xFF.toByte(),
            0xFF.toByte(),
            blockCount.toByte()
        ) + blockList

        val command = byteArrayOf(
            (1 + 1 + currentIdm.size + cmdData.size).toByte(),
            COMMAND_READ
        ) + currentIdm + cmdData

        Log.d(TAG, "Sending read command: ${command.toHexString()}")

        return try {
            val response = nfcF.transceive(command)
            Log.d(TAG, "Read response: ${response.toHexString()}")

            if (response.size < 13 || response[1] != 0x07.toByte()) {
                Log.e(TAG, "Invalid read response header")
                return null
            }
            val statusFlag1 = response[10]
            val statusFlag2 = response[11]
            if (statusFlag1 != 0x00.toByte() || statusFlag2 != 0x00.toByte()) {
                Log.e(TAG, "Read failed: status flags $statusFlag1 $statusFlag2")
                return null
            }
            val blocksReturned = response[12].toInt() and 0xFF
            val dataStart = 13
            val dataEnd = dataStart + blocksReturned * 16
            if (response.size < dataEnd) {
                Log.e(TAG, "Incomplete block data in response")
                return null
            }
            response.copyOfRange(dataStart, dataEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading blocks", e)
            null
        }
    }
}
