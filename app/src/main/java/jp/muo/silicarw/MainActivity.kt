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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.muo.silicarw.nfc.NfcReadOperations
import jp.muo.silicarw.nfc.NfcWriteOperations
import jp.muo.silicarw.ui.tabs.HistoryTabContent
import jp.muo.silicarw.ui.tabs.ReadTabContent
import jp.muo.silicarw.ui.tabs.WriteTabContent
import jp.muo.silicarw.ui.theme.SiliCaRWTheme
import jp.muo.silicarw.util.hexStringToByteArray
import jp.muo.silicarw.util.tryAcceptHexInput
import jp.muo.silicarw.util.toHexString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class WriteCommandType(val label: String) {
    IDM("IDm / PMm"),
    SYSTEM_CODES("System Codes"),
    SERVICE_CODES("Service Codes"),
    RAW_BLOCK("Raw Block")
}

enum class MainTab(val label: String) {
    READ("Read"),
    WRITE("Write"),
    HISTORY("History")
}

data class HistoryEntry(
    val id: Long,
    val timestamp: String,
    val content: String
)

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
    private var isReadMode by mutableStateOf(false)
    private var pendingWriteRequest by mutableStateOf<WriteRequest?>(null)
    private var debugInfo by mutableStateOf("")
    private var lastErrorCommand by mutableStateOf("未取得")
    private var readOptionLastError by mutableStateOf(true)
    private var readOptionSystemCodes by mutableStateOf(false)
    private var readOptionServiceCodes by mutableStateOf(false)
    private var readOptionCustomBlock by mutableStateOf(false)
    private var readCustomBlockNumber by mutableStateOf("0")
    private var selectedTab by mutableStateOf(MainTab.READ)
    private val readHistory = mutableStateListOf<HistoryEntry>()
    private val historyFileName = "read_history.json"

    companion object {
        private const val TAG = "NFCWriter"
        private const val MAX_SYSTEM = 4
        private const val MAX_SERVICE = 4
        private const val IDLE_STATUS_MESSAGE = "ReadまたはWriteボタンを押してからタグをかざしてください"
        private val DEFAULT_PMM = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xFF.toByte(),
                                               0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
    }

    private data class WriteRequest(
        val block: Int,
        val data: ByteArray,
        val description: String,
        val verifyIdm: ByteArray? = null
    )

    private fun setIdleStatus() {
        statusMessage = IDLE_STATUS_MESSAGE
        debugInfo = "Idle"
    }

    private fun appendReadHistory(summary: String) {
        val now = System.currentTimeMillis()
        val formatter = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
        val entry = HistoryEntry(
            id = now,
            timestamp = formatter.format(Date(now)),
            content = summary
        )
        readHistory.add(0, entry)
        persistHistory()
    }

    private fun historyFile(): File = File(filesDir, historyFileName)

    private fun loadHistoryFromFile(): List<HistoryEntry> {
        val file = historyFile()
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            val json = JSONArray(file.readText())
            buildList {
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    add(
                        HistoryEntry(
                            id = obj.optLong("id"),
                            timestamp = obj.optString("timestamp"),
                            content = obj.optString("content")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history", e)
            emptyList()
        }
    }

    private fun persistHistory() {
        try {
            val array = JSONArray()
            readHistory.forEach { entry ->
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("timestamp", entry.timestamp)
                obj.put("content", entry.content)
                array.put(obj)
            }
            historyFile().writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save history", e)
        }
    }

    private fun deleteHistoryEntry(entryId: Long) {
        val index = readHistory.indexOfFirst { it.id == entryId }
        if (index >= 0) {
            readHistory.removeAt(index)
            persistHistory()
        }
    }

    private fun clearHistoryEntries() {
        if (readHistory.isNotEmpty()) {
            readHistory.clear()
            persistHistory()
        }
    }

    private fun onWriteButtonClick() {
        val request = buildWriteRequest(reportErrors = true) ?: return
        Log.d(TAG, "Prepared write request: ${request.description} block=${request.block}")
        isWriteMode = true
        isReadMode = false
        pendingWriteRequest = request
        statusMessage = "書き込み待機中...\n${request.description}\nタグをかざしてください"
        Log.d(TAG, "Write mode activated, waiting for tag...")
    }

    private fun onReadButtonClick() {
        Log.d(TAG, "onReadButtonClick called")
        isReadMode = true
        isWriteMode = false
        pendingWriteRequest = null
        statusMessage = "読み取り待機中...\nタグをかざしてください"
        debugInfo = "Read mode waiting"
    }

    private fun cancelPendingOperation() {
        if (!isReadMode && !isWriteMode) {
            return
        }
        Log.d(TAG, "Cancelling pending operation")
        isReadMode = false
        isWriteMode = false
        pendingWriteRequest = null
        setIdleStatus()
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
                    data = NfcWriteOperations.padToBlock(bytes),
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
                    data = NfcWriteOperations.padToBlock(swapped),
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

        readHistory.addAll(loadHistoryFromFile())

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
            setIdleStatus()
            debugInfo = "NFC: Enabled & Ready"
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        enableEdgeToEdge()
        setContent {
            val canWrite = !isWriteMode && !isReadMode && buildWriteRequest(reportErrors = false) != null
            val canRead = !isReadMode && !isWriteMode
            val canCancel = isReadMode || isWriteMode
            SiliCaRWTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NFCWriterScreen(
                        statusMessage = statusMessage,
                        currentIdm = currentIdm,
                        selectedTab = selectedTab,
                        onTabSelected = { newTab -> if (!isReadMode && !isWriteMode) selectedTab = newTab },
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
                        readOptionLastError = readOptionLastError,
                        onReadOptionLastErrorChange = { readOptionLastError = it },
                        readOptionSystemCodes = readOptionSystemCodes,
                        onReadOptionSystemCodesChange = { readOptionSystemCodes = it },
                        readOptionServiceCodes = readOptionServiceCodes,
                        onReadOptionServiceCodesChange = { readOptionServiceCodes = it },
                        readOptionCustomBlock = readOptionCustomBlock,
                        onReadOptionCustomBlockChange = { readOptionCustomBlock = it },
                        readCustomBlockNumber = readCustomBlockNumber,
                        onReadCustomBlockNumberChange = { readCustomBlockNumber = it },
                        readHistory = readHistory,
                        onReadClick = { onReadButtonClick() },
                        onWriteClick = { onWriteButtonClick() },
                        onCancelClick = { cancelPendingOperation() },
                        isWriteMode = isWriteMode,
                        isReadMode = isReadMode,
                        isReadButtonEnabled = canRead,
                        isWriteButtonEnabled = canWrite,
                        isCancelButtonEnabled = canCancel,
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

        if (!isReadMode && !isWriteMode) {
            Log.d(TAG, "No pending operation – ignoring tag")
            setIdleStatus()
            return
        }
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

                val writeResult = NfcWriteOperations.writeBlock(nfcF, idm, pendingRequest.block, pendingRequest.data)
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
            } else if (isReadMode) {
                Log.d(TAG, "Manual read mode active")
                val outputs = mutableListOf<String>()

                val idmPmm = NfcReadOperations.readIdmPmm(nfcF, idm)
                if (idmPmm != null) {
                    outputs += "IDm: ${idmPmm.first}"
                    outputs += "PMm: ${idmPmm.second}"
                } else {
                    outputs += "IDm: $currentIdm"
                    outputs += "PMm: 取得失敗"
                }

                if (readOptionLastError) {
                    val lastError = NfcReadOperations.fetchLastErrorCommand(nfcF, idm)
                    if (lastError != null) {
                        lastErrorCommand = lastError
                        outputs += "Last Error: $lastError"
                    } else {
                        lastErrorCommand = "取得失敗"
                        outputs += "Last Error: 取得失敗"
                    }
                }

                if (readOptionSystemCodes) {
                    val codes = NfcReadOperations.readCodeListBlock(
                        nfcF,
                        idm,
                        blockNumber = 0x85,
                        swapBytes = false
                    )
                    outputs += "System Codes: ${NfcReadOperations.formatCodeListDisplay(codes)}"
                }

                if (readOptionServiceCodes) {
                    val codes = NfcReadOperations.readCodeListBlock(
                        nfcF,
                        idm,
                        blockNumber = 0x84,
                        swapBytes = true
                    )
                    outputs += "Service Codes: ${NfcReadOperations.formatCodeListDisplay(codes)}"
                }

                if (readOptionCustomBlock) {
                    outputs += NfcReadOperations.readCustomBlockDisplay(
                        nfcF,
                        idm,
                        readCustomBlockNumber
                    )
                }

                val summary = outputs.joinToString("\n")
                statusMessage = "読み取り結果:\n$summary"
                debugInfo = "Manual Read OK"
                appendReadHistory(summary)
                isReadMode = false
            } else {
                Log.w(TAG, "Tag received but no active operation")
                setIdleStatus()
            }

        } catch (e: Exception) {
            statusMessage = "エラー: ${e.message}\n現在のIDm: $currentIdm"
            Log.e(TAG, "Error handling tag", e)
            debugInfo = "Error: ${e.message}"
            isWriteMode = false
            isReadMode = false
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

}

@Composable
fun NFCWriterScreen(
    statusMessage: String,
    currentIdm: String,
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
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
    readOptionLastError: Boolean,
    onReadOptionLastErrorChange: (Boolean) -> Unit,
    readOptionSystemCodes: Boolean,
    onReadOptionSystemCodesChange: (Boolean) -> Unit,
    readOptionServiceCodes: Boolean,
    onReadOptionServiceCodesChange: (Boolean) -> Unit,
    readOptionCustomBlock: Boolean,
    onReadOptionCustomBlockChange: (Boolean) -> Unit,
    readCustomBlockNumber: String,
    onReadCustomBlockNumberChange: (String) -> Unit,
    readHistory: List<HistoryEntry>,
    onReadClick: () -> Unit,
    onWriteClick: () -> Unit,
    onCancelClick: () -> Unit,
    isWriteMode: Boolean,
    isReadMode: Boolean,
    isReadButtonEnabled: Boolean,
    isWriteButtonEnabled: Boolean,
    isCancelButtonEnabled: Boolean,
    debugInfo: String,
    lastErrorCommand: String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    val controlsEnabled = !isReadMode && !isWriteMode
    fun handleCustomBlockInput(newValue: String) {
        if (!controlsEnabled) return
        if (newValue.isEmpty()) {
            onReadCustomBlockNumberChange("")
            return
        }
        if (newValue.length > 3 || newValue.any { !it.isDigit() }) {
            return
        }
        val parsed = newValue.toIntOrNull()
        if (parsed != null && parsed in 0..255) {
            onReadCustomBlockNumberChange(parsed.toString())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
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

                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    MainTab.values().forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { if (controlsEnabled) onTabSelected(tab) },
                            enabled = controlsEnabled || selectedTab == tab,
                            text = { Text(tab.label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    MainTab.READ -> {
                        ReadTabContent(
                            readOptionLastError = readOptionLastError,
                            onReadOptionLastErrorChange = onReadOptionLastErrorChange,
                            readOptionSystemCodes = readOptionSystemCodes,
                            onReadOptionSystemCodesChange = onReadOptionSystemCodesChange,
                            readOptionServiceCodes = readOptionServiceCodes,
                            onReadOptionServiceCodesChange = onReadOptionServiceCodesChange,
                            readOptionCustomBlock = readOptionCustomBlock,
                            onReadOptionCustomBlockChange = onReadOptionCustomBlockChange,
                            readCustomBlockNumber = readCustomBlockNumber,
                            onReadCustomBlockNumberChange = { handleCustomBlockInput(it) },
                            controlsEnabled = controlsEnabled,
                            isReadMode = isReadMode,
                            isReadButtonEnabled = isReadButtonEnabled,
                            isCancelButtonEnabled = isCancelButtonEnabled,
                            onReadClick = onReadClick,
                            onCancelClick = onCancelClick
                        )
                    }

                    MainTab.WRITE -> {
                        WriteTabContent(
                            controlsEnabled = controlsEnabled,
                            selectedCommand = selectedCommand,
                            onCommandChange = onCommandChange,
                            idmInput = idmInput,
                            onInputIdmChange = onInputIdmChange,
                            pmmInput = pmmInput,
                            onPmmChange = onPmmChange,
                            systemCodesInput = systemCodesInput,
                            onSystemCodesChange = onSystemCodesChange,
                            serviceCodesInput = serviceCodesInput,
                            onServiceCodesChange = onServiceCodesChange,
                            rawBlockNumberInput = rawBlockNumberInput,
                            onRawBlockNumberChange = onRawBlockNumberChange,
                            rawDataInput = rawDataInput,
                            onRawDataChange = onRawDataChange
                        )

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

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = onCancelClick,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = isCancelButtonEnabled
                        ) {
                            Text(
                                text = "Cancel",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    MainTab.HISTORY -> {
                        HistoryTabContent(
                            readHistory = readHistory,
                            onCopyEntry = { clipboardManager.setText(AnnotatedString(it)) },
                            onDeleteEntry = { entry -> this@MainActivity.deleteHistoryEntry(entry.id) },
                            onClearAll = { this@MainActivity.clearHistoryEntries() }
                        )
                    }
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
