package jp.muo.silicarw

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.min
import jp.muo.silicarw.ui.theme.SiliCaRWTheme

enum class WriteCommandType(val label: String) {
    IDM("IDm / PMm"),
    SYSTEM_CODES("System Codes"),
    SERVICE_CODES("Service Codes"),
    RAW_BLOCK("Raw Block")
}

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    private var statusMessage by mutableStateOf("初期化中...")
    private var currentIdm by mutableStateOf("")
    private var idmInput by mutableStateOf("DEADBEEF01010101")
    private var pmmInput by mutableStateOf("")
    private var systemCodesInput by mutableStateOf("")
    private var serviceCodesInput by mutableStateOf("")
    private var rawBlockNumberInput by mutableStateOf("0")
    private var rawDataInput by mutableStateOf("")
    private var selectedCommand by mutableStateOf(WriteCommandType.IDM)
    private var isWriteMode by mutableStateOf(false)
    private var pendingWriteRequest by mutableStateOf<WriteRequest?>(null)
    private var debugInfo by mutableStateOf("")
    private var lastErrorCommand by mutableStateOf("未取得")

    companion object {
        private const val TAG = "NFCWriter"
        private const val COMMAND_READ = 0x06.toByte()
        private const val COMMAND_WRITE = 0x08.toByte()
        private const val MAX_SYSTEM = 4
        private const val MAX_SERVICE = 4
        private val DEFAULT_PMM = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xFF.toByte(),
                                               0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
    }

    private data class WriteRequest(
        val block: Int,
        val data: ByteArray,
        val description: String,
        val verifyIdm: ByteArray? = null
    )

    private data class BlockOperationResult(
        val success: Boolean,
        val errorMessage: String? = null
    )

    private fun onWriteButtonClick() {
        val request = buildWriteRequest(reportErrors = true) ?: return
        Log.d(TAG, "Prepared write request: ${request.description} block=${request.block}")
        isWriteMode = true
        pendingWriteRequest = request
        statusMessage = "書き込み待機中...\n${request.description}\nタグをかざしてください"
        Log.d(TAG, "Write mode activated, waiting for tag...")
    }

    private fun isValidHex(hex: String): Boolean {
        return hex.all { it in '0'..'9' || it in 'A'..'F' }
    }

    private fun buildWriteRequest(reportErrors: Boolean): WriteRequest? {
        fun fail(message: String): WriteRequest? {
            if (reportErrors) {
                statusMessage = "エラー: $message"
                debugInfo = "Validation error"
            }
            return null
        }

        fun sanitize(raw: String, allowEmpty: Boolean = false): String? {
            val cleaned = raw.uppercase().replace(" ", "").replace(",", "")
            if (cleaned.isEmpty()) {
                return if (allowEmpty) "" else null
            }
            return if (isValidHex(cleaned)) cleaned else null
        }

        return when (selectedCommand) {
            WriteCommandType.IDM -> {
                val cleanIdm = sanitize(idmInput) ?: return fail("IDmは16桁の16進数で入力してください")
                if (cleanIdm.length != 16) return fail("IDmは16桁の16進数で入力してください")
                val idmBytes = hexStringToByteArray(cleanIdm)

                val cleanPmm = sanitize(pmmInput, allowEmpty = true)
                    ?: return fail("PMmは16桁の16進数で入力してください")
                val pmmBytes = if (cleanPmm.isEmpty()) {
                    DEFAULT_PMM
                } else {
                    if (cleanPmm.length != 16) return fail("PMmは16桁の16進数で入力してください")
                    hexStringToByteArray(cleanPmm)
                }

                WriteRequest(
                    block = 0x83,
                    data = idmBytes + pmmBytes,
                    description = "IDm/PMm 書き込み",
                    verifyIdm = idmBytes
                )
            }

            WriteCommandType.SYSTEM_CODES -> {
                val clean = sanitize(systemCodesInput) ?: return fail("システムコードは16進数で入力してください")
                if (clean.length % 4 != 0) return fail("システムコードは2バイト（4桁）単位で入力してください")
                val codeCount = clean.length / 4
                if (codeCount == 0 || codeCount > MAX_SYSTEM) {
                    return fail("システムコードは1〜${MAX_SYSTEM}個まで設定できます")
                }
                val bytes = hexStringToByteArray(clean)
                WriteRequest(
                    block = 0x85,
                    data = padToBlock(bytes),
                    description = "System Codes 書き込み"
                )
            }

            WriteCommandType.SERVICE_CODES -> {
                val clean = sanitize(serviceCodesInput) ?: return fail("サービスコードは16進数で入力してください")
                if (clean.length % 4 != 0) return fail("サービスコードは2バイト（4桁）単位で入力してください")
                val codeCount = clean.length / 4
                if (codeCount == 0 || codeCount > MAX_SERVICE) {
                    return fail("サービスコードは1〜${MAX_SERVICE}個まで設定できます")
                }
                val rawBytes = hexStringToByteArray(clean)
                val swapped = ByteArray(rawBytes.size)
                for (i in rawBytes.indices step 2) {
                    swapped[i] = rawBytes[i + 1]
                    swapped[i + 1] = rawBytes[i]
                }
                WriteRequest(
                    block = 0x84,
                    data = padToBlock(swapped),
                    description = "Service Codes 書き込み"
                )
            }

            WriteCommandType.RAW_BLOCK -> {
                val blockNumber = rawBlockNumberInput.toIntOrNull()
                    ?: return fail("ブロック番号は数値で入力してください")
                if (blockNumber !in 0..11) return fail("ブロック番号は0〜11の範囲で入力してください")

                val clean = sanitize(rawDataInput) ?: return fail("データは16進数で入力してください")
                if (clean.length != 32) return fail("データは32桁（16バイト）で入力してください")

                WriteRequest(
                    block = blockNumber,
                    data = hexStringToByteArray(clean),
                    description = "Raw Block $blockNumber 書き込み"
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate called")

        // NFC初期化
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.e(TAG, "NFC is not available on this device")
            statusMessage = "このデバイスはNFCに対応していません"
            debugInfo = "NFC: Not available"
        } else if (!nfcAdapter!!.isEnabled) {
            Log.w(TAG, "NFC is available but disabled")
            statusMessage = "NFCが無効です。設定でNFCを有効にしてください"
            debugInfo = "NFC: Disabled"
        } else {
            Log.d(TAG, "NFC is available and enabled")
            statusMessage = "NFCタグを待機中..."
            debugInfo = "NFC: Enabled & Ready"
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        enableEdgeToEdge()
        setContent {
            val canWrite = !isWriteMode && buildWriteRequest(reportErrors = false) != null
            SiliCaRWTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NFCWriterScreen(
                        statusMessage = statusMessage,
                        currentIdm = currentIdm,
                        selectedCommand = selectedCommand,
                        onCommandChange = { selectedCommand = it },
                        idmInput = idmInput,
                        onInputIdmChange = { idmInput = it },
                        pmmInput = pmmInput,
                        onPmmChange = { pmmInput = it },
                        systemCodesInput = systemCodesInput,
                        onSystemCodesChange = { systemCodesInput = it },
                        serviceCodesInput = serviceCodesInput,
                        onServiceCodesChange = { serviceCodesInput = it },
                        rawBlockNumberInput = rawBlockNumberInput,
                        onRawBlockNumberChange = { rawBlockNumberInput = it },
                        rawDataInput = rawDataInput,
                        onRawDataChange = { rawDataInput = it },
                        onWriteClick = { onWriteButtonClick() },
                        isWriteMode = isWriteMode,
                        isWriteButtonEnabled = canWrite,
                        debugInfo = debugInfo,
                        lastErrorCommand = lastErrorCommand,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        // onCreateで呼ばれた場合のIntent処理
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        Log.d(TAG, "handleIntent called with action: ${intent.action}")
        debugInfo = "Intent: ${intent.action?.substringAfterLast('.') ?: "null"}"

        when (intent.action) {
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                Log.d(TAG, "NFC action detected: ${intent.action}")
                val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
                }

                if (tag != null) {
                    Log.d(TAG, "Tag found in handleIntent")
                    debugInfo = "Processing tag..."
                    handleTag(tag)
                } else {
                    Log.e(TAG, "Tag is null in handleIntent")
                    debugInfo = "Error: Tag is null"
                }
            }
            else -> {
                debugInfo = "No NFC action"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        if (nfcAdapter != null) {
            // NFC-Fタグのみを受け取るようにフィルタリング
            val techListArray = arrayOf(arrayOf(NfcF::class.java.name))

            nfcAdapter?.enableForegroundDispatch(
                this,
                pendingIntent,
                null,  // IntentFilterはnullでOK（すべてのNFCを受け取る）
                techListArray  // NFC-Fのみにフィルタ
            )
            Log.d(TAG, "Foreground dispatch enabled for NFC-F")
            debugInfo = "NFC: FG Dispatch ON (NFC-F)"
        } else {
            Log.e(TAG, "Cannot enable foreground dispatch - nfcAdapter is null")
            debugInfo = "NFC: Adapter NULL"
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")

        if (nfcAdapter != null) {
            nfcAdapter?.disableForegroundDispatch(this)
            Log.d(TAG, "Foreground dispatch disabled")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)  // 重要: 新しいIntentを設定
        Log.d(TAG, "onNewIntent called")
        handleIntent(intent)
    }

    private fun handleTag(tag: Tag) {
        Log.d(TAG, "handleTag called")
        Log.d(TAG, "Tag ID: ${tag.id.toHexString()}")
        Log.d(TAG, "Tag technologies: ${tag.techList.joinToString()}")
        debugInfo = "Tag detected!"

        val nfcF = NfcF.get(tag)
        if (nfcF == null) {
            Log.e(TAG, "NfcF.get(tag) returned null - not an NFC-F tag")
            statusMessage = "NFC-F タグではありません\nタグ技術: ${tag.techList.joinToString()}"
            debugInfo = "Error: Not NFC-F"
            return
        }

        Log.d(TAG, "NfcF tag detected")
        debugInfo = "NFC-F detected"

        var shouldCloseConnection = true

        try {
            nfcF.connect()
            Log.d(TAG, "NfcF connected successfully")

            // 現在のIDmを読み取り
            val idm = nfcF.tag.id
            currentIdm = idm.toHexString()
            Log.d(TAG, "Current IDm: $currentIdm")
            Log.d(TAG, "Manufacturer: ${nfcF.manufacturer.toHexString()}")
            Log.d(TAG, "System Code: ${nfcF.systemCode.toHexString()}")

            // エラーコマンド履歴を取得
            val errorCommand = fetchLastErrorCommand(nfcF, idm)
            if (errorCommand != null) {
                lastErrorCommand = errorCommand
                Log.d(TAG, "Last error command: $errorCommand")
            } else {
                lastErrorCommand = "取得失敗"
                Log.w(TAG, "Unable to read last error command")
            }

            val pendingRequest = pendingWriteRequest
            if (isWriteMode && pendingRequest != null) {
                Log.d(TAG, "Write mode active for ${pendingRequest.description}")
                debugInfo = "Writing..."
                statusMessage = "現在のIDm: ${currentIdm}\n${pendingRequest.description}実行中..."

                val targetIdmHex = pendingRequest.verifyIdm?.toHexString()
                if (targetIdmHex != null && currentIdm.equals(targetIdmHex, ignoreCase = true)) {
                    statusMessage = "書き込み済み\n現在のIDm: $currentIdm"
                    debugInfo = "Already Written"
                    isWriteMode = false
                    pendingWriteRequest = null
                    return
                }

                val writeResult = writeBlock(nfcF, idm, pendingRequest.block, pendingRequest.data)
                if (writeResult.success) {
                    if (pendingRequest.verifyIdm != null) {
                        val targetIdm = pendingRequest.verifyIdm.toHexString()

                        // 書き込み後、タグを切断して再接続し、IDmを確認
                        val oldIdmForDisplay = currentIdm
                        try {
                            nfcF.close()
                            shouldCloseConnection = false
                            Thread.sleep(100)
                            nfcF.connect()

                            val newReadIdm = nfcF.tag.id.toHexString()
                            currentIdm = newReadIdm
                            Log.d(TAG, "After write, IDm: $newReadIdm")

                            if (newReadIdm.equals(targetIdm, ignoreCase = true)) {
                                statusMessage = "書き込み成功!\n旧IDm: $oldIdmForDisplay\n新IDm: $newReadIdm"
                                debugInfo = "Write OK"
                            } else {
                                statusMessage = "書き込み失敗\n期待: $targetIdm\n実際: $newReadIdm"
                                debugInfo = "Write FAILED"
                            }
                            shouldCloseConnection = true
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not verify write result: ${e.message}")
                            statusMessage = "書き込み完了\n（確認失敗）\n対象IDm: $targetIdm"
                            debugInfo = "Write Done (unverified)"
                            shouldCloseConnection = false
                        }
                    } else {
                        statusMessage = "書き込み成功\n${pendingRequest.description}"
                        debugInfo = "Write OK"
                    }
                } else {
                    val errorMessage = writeResult.errorMessage ?: "詳細情報なし"
                    statusMessage = "書き込み失敗\n${pendingRequest.description}\n$errorMessage"
                    debugInfo = "Write FAILED: $errorMessage"
                }

                isWriteMode = false
                pendingWriteRequest = null
            } else {
                // 読み取り専用モード
                Log.d(TAG, "Read-only mode, displaying IDm")
                statusMessage = "タグ検出\n現在のIDm: $currentIdm"
                debugInfo = "Read OK"
            }

        } catch (e: Exception) {
            statusMessage = "エラー: ${e.message}\n現在のIDm: $currentIdm"
            Log.e(TAG, "Error handling tag", e)
            debugInfo = "Error: ${e.message}"
            isWriteMode = false
            pendingWriteRequest = null
        } finally {
            if (shouldCloseConnection) {
                try {
                    nfcF.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing connection", e)
                }
            }
        }
    }

    private fun writeBlock(
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
            0x01,           // service count
            0xFF.toByte(),  // service code (little endian)
            0xFF.toByte(),
            0x01,           // block count
            0x80.toByte(),  // block list element (2-byte mode)
            block.toByte()  // block number
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

    private fun readSystemBlocks(nfcF: NfcF, currentIdm: ByteArray, blockList: ByteArray): ByteArray? {
        if (blockList.isEmpty() || blockList.size % 2 != 0) {
            Log.e(TAG, "Invalid block list for read: ${blockList.toHexString()}")
            return null
        }

        val blockCount = blockList.size / 2
        val cmdData = byteArrayOf(
            0x01,           // service count
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

    private fun fetchLastErrorCommand(nfcF: NfcF, currentIdm: ByteArray): String? {
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

    private fun padToBlock(bytes: ByteArray): ByteArray {
        return if (bytes.size >= 16) {
            bytes.copyOf(16)
        } else {
            bytes + ByteArray(16 - bytes.size)
        }
    }

    private fun ByteArray.toHexString(): String {
        return this.joinToString("") { "%02X".format(it) }
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

@Composable
fun NFCWriterScreen(
    statusMessage: String,
    currentIdm: String,
    selectedCommand: WriteCommandType,
    onCommandChange: (WriteCommandType) -> Unit,
    idmInput: String,
    onInputIdmChange: (String) -> Unit,
    pmmInput: String,
    onPmmChange: (String) -> Unit,
    systemCodesInput: String,
    onSystemCodesChange: (String) -> Unit,
    serviceCodesInput: String,
    onServiceCodesChange: (String) -> Unit,
    rawBlockNumberInput: String,
    onRawBlockNumberChange: (String) -> Unit,
    rawDataInput: String,
    onRawDataChange: (String) -> Unit,
    onWriteClick: () -> Unit,
    isWriteMode: Boolean,
    isWriteButtonEnabled: Boolean,
    debugInfo: String,
    lastErrorCommand: String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "SiliCa Tag Writer",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = debugInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isWriteMode)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "書き込み対象",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                WriteCommandType.values().forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable(enabled = !isWriteMode) { onCommandChange(type) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedCommand == type,
                            onClick = { onCommandChange(type) },
                            enabled = !isWriteMode
                        )
                        Text(
                            text = type.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedCommand) {
                    WriteCommandType.IDM -> {
                        OutlinedTextField(
                            value = idmInput,
                            onValueChange = { newValue ->
                                tryAcceptHexInput(newValue, 16)?.let(onInputIdmChange)
                            },
                            label = { Text("新しいIDm (16桁16進数)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isWriteMode,
                            supportingText = { Text("例: DEADBEEF01010101 (8バイト)") }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = pmmInput,
                            onValueChange = { newValue ->
                                tryAcceptHexInput(newValue, 16)?.let(onPmmChange)
                            },
                            label = { Text("PMm (任意 16桁)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isWriteMode,
                            supportingText = { Text("省略時はデフォルト値を使用します") }
                        )
                    }

                    WriteCommandType.SYSTEM_CODES -> {
                        OutlinedTextField(
                            value = systemCodesInput,
                            onValueChange = { newValue ->
                                tryAcceptHexInput(newValue, 16)?.let(onSystemCodesChange)
                            },
                            label = { Text("System Codes") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isWriteMode,
                            supportingText = { Text("最大4件・各4桁 (例: 12FC8000)") }
                        )
                    }

                    WriteCommandType.SERVICE_CODES -> {
                        OutlinedTextField(
                            value = serviceCodesInput,
                            onValueChange = { newValue ->
                                tryAcceptHexInput(newValue, 16)?.let(onServiceCodesChange)
                            },
                            label = { Text("Service Codes") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isWriteMode,
                            supportingText = { Text("最大4件・各4桁 (例: 100B200B)") }
                        )
                    }

                    WriteCommandType.RAW_BLOCK -> {
                        OutlinedTextField(
                            value = rawBlockNumberInput,
                            onValueChange = { newValue ->
                                if (newValue.length <= 2 && newValue.all { it.isDigit() }) {
                                    onRawBlockNumberChange(newValue)
                                } else if (newValue.isEmpty()) {
                                    onRawBlockNumberChange("")
                                }
                            },
                            label = { Text("ブロック番号 (0〜11)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isWriteMode,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = rawDataInput,
                            onValueChange = { newValue ->
                                tryAcceptHexInput(newValue, 32)?.let(onRawDataChange)
                            },
                            label = { Text("16バイトのデータ (32桁16進数)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isWriteMode,
                            supportingText = { Text("例: 00112233445566778899AABBCCDDEEFF") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onWriteClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isWriteButtonEnabled
                ) {
                    Text(
                        text = if (isWriteMode) "書き込み待機中..." else "Write",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (currentIdm.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "最後に読み取ったIDm:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = currentIdm,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "最後に取得したエラーコマンド:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (lastErrorCommand.isNotBlank()) lastErrorCommand else "未取得",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun tryAcceptHexInput(newValue: String, maxLength: Int? = null): String? {
    val candidate = newValue.uppercase()
    if (!candidate.all { it.isHexDigitChar() }) {
        return null
    }
    if (maxLength != null && candidate.length > maxLength) {
        return null
    }
    return candidate
}

private fun Char.isHexDigitChar(): Boolean {
    val upper = this.uppercaseChar()
    return upper in '0'..'9' || upper in 'A'..'F'
}
