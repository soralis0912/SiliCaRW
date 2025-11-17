package jp.muo.silicarw.nfc

import android.nfc.tech.NfcF
import android.util.Log
import jp.muo.silicarw.util.toHexString

data class BlockOperationResult(
    val success: Boolean,
    val errorMessage: String? = null
)

object NfcWriteOperations {
    private const val COMMAND_WRITE = 0x08.toByte()
    private const val TAG = "NfcWriteOps"

    fun padToBlock(bytes: ByteArray): ByteArray {
        return if (bytes.size >= 16) {
            bytes.copyOf(16)
        } else {
            bytes + ByteArray(16 - bytes.size)
        }
    }

    fun writeBlock(
        nfcF: NfcF,
        currentIdm: ByteArray,
        block: Int,
        data: ByteArray
    ): BlockOperationResult {
        if (block !in 0..0xFF) {
            val message = "Invalid block number: $block"
            Log.e(TAG, message)
            return BlockOperationResult(false, message)
        }
        if (data.size != 16) {
            val message = "Data must be exactly 16 bytes, got ${data.size}"
            Log.e(TAG, message)
            return BlockOperationResult(false, message)
        }

        val cmdData = byteArrayOf(
            0x01,
            0xFF.toByte(),
            0xFF.toByte(),
            0x01,
            0x80.toByte(),
            block.toByte()
        ) + data

        val command = byteArrayOf(
            (1 + 1 + currentIdm.size + cmdData.size).toByte(),
            COMMAND_WRITE
        ) + currentIdm + cmdData

        Log.d(TAG, "Sending block $block write command: ${command.toHexString()}")

        return try {
            val response = nfcF.transceive(command)
            Log.d(TAG, "Response: ${response.toHexString()}")

            if (response.size >= 11 && response[1] == 0x09.toByte()) {
                val statusFlag1 = response[9]
                val statusFlag2 = response[10]
                if (statusFlag1 == 0x00.toByte() && statusFlag2 == 0x00.toByte()) {
                    BlockOperationResult(true, null)
                } else {
                    val message = "ステータスフラグ: %02X %02X".format(
                        statusFlag1,
                        statusFlag2
                    )
                    Log.e(TAG, "Write failed, $message")
                    BlockOperationResult(false, message)
                }
            } else {
                val message = "不正なレスポンス: ${response.toHexString()}"
                Log.e(TAG, message)
                BlockOperationResult(false, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to tag", e)
            val message = "通信エラー: ${e.message ?: e.javaClass.simpleName}"
            BlockOperationResult(false, message)
        }
    }
}
