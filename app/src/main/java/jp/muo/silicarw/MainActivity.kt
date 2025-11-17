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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.muo.silicarw.ui.theme.SiliCaRWTheme

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    private var statusMessage by mutableStateOf("初期化中...")
    private var currentIdm by mutableStateOf("")
    private var inputIdm by mutableStateOf("DEADBEEF01010101")
    private var isWriteMode by mutableStateOf(false)
    private var pendingWriteIdm by mutableStateOf("")
    private var debugInfo by mutableStateOf("")

    companion object {
        private const val TAG = "NFCWriter"
        private const val COMMAND_WRITE = 0x08.toByte()
        private val DEFAULT_PMM = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xFF.toByte(),
                                               0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
    }

    private fun onWriteButtonClick(hexIdm: String) {
        Log.d(TAG, "onWriteButtonClick called with IDm: $hexIdm")

        if (!isValidHex(hexIdm) || hexIdm.length != 16) {
            Log.e(TAG, "Invalid IDm format: $hexIdm")
            statusMessage = "エラー: IDmは16桁の16進数で入力してください"
            return
        }

        isWriteMode = true
        pendingWriteIdm = hexIdm
        statusMessage = "書き込み待機中...\nタグをかざしてください"
        Log.d(TAG, "Write mode activated, waiting for tag...")
    }

    private fun isValidHex(hex: String): Boolean {
        return hex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
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
            SiliCaRWTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NFCWriterScreen(
                        statusMessage = statusMessage,
                        currentIdm = currentIdm,
                        inputIdm = inputIdm,
                        onInputIdmChange = { inputIdm = it },
                        onWriteClick = { onWriteButtonClick(inputIdm) },
                        isWriteMode = isWriteMode,
                        debugInfo = debugInfo,
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

            if (isWriteMode && pendingWriteIdm.isNotEmpty()) {
                // 書き込みモード
                Log.d(TAG, "Write mode active, pending IDm: $pendingWriteIdm")
                debugInfo = "Writing..."
                statusMessage = "現在のIDm: ${currentIdm}\n書き込み中..."

                val newIdm = hexStringToByteArray(pendingWriteIdm)
                Log.d(TAG, "New IDm bytes: ${newIdm.toHexString()}")

                // 書き込み対象のIDm = 現在読み取ったIDmの場合、既に書き込み済み
                if (currentIdm.equals(newIdm.toHexString(), ignoreCase = true)) {
                    statusMessage = "書き込み済み\n現在のIDm: $currentIdm"
                    Log.d(TAG, "IDm already matches target - write already successful")
                    debugInfo = "Already Written"
                } else {
                    // 書き込み実行
                    val oldIdmForDisplay = currentIdm
                    writeIdm(nfcF, idm, newIdm)

                    // 書き込み後、タグを切断して再接続し、IDmを確認
                    try {
                        nfcF.close()
                        shouldCloseConnection = false  // 既にクローズ済み
                        Thread.sleep(100)  // 短い待機
                        nfcF.connect()

                        // 書き込み後のIDmを読み取り
                        val newReadIdm = nfcF.tag.id.toHexString()
                        currentIdm = newReadIdm
                        Log.d(TAG, "After write, IDm: $newReadIdm")

                        // 新しいIDmと一致しているか確認
                        if (newReadIdm.equals(newIdm.toHexString(), ignoreCase = true)) {
                            statusMessage = "書き込み成功!\n" +
                                    "旧IDm: $oldIdmForDisplay\n" +
                                    "新IDm: $newReadIdm"
                            Log.d(TAG, "Write successful - IDm matches")
                            debugInfo = "Write OK"
                        } else {
                            statusMessage = "書き込み失敗\n" +
                                    "期待: ${newIdm.toHexString()}\n" +
                                    "実際: $newReadIdm"
                            Log.e(TAG, "Write failed - IDm mismatch")
                            debugInfo = "Write FAILED"
                        }

                        // 再接続したので、再度クローズが必要
                        shouldCloseConnection = true
                    } catch (e: Exception) {
                        // 再接続失敗時は警告だけ表示
                        Log.w(TAG, "Could not verify write result: ${e.message}")
                        statusMessage = "書き込み完了\n（確認失敗）\n対象IDm: ${newIdm.toHexString()}"
                        debugInfo = "Write Done (unverified)"
                        shouldCloseConnection = false  // 既にクローズ済み
                    }
                }

                // 書き込みモードをリセット
                isWriteMode = false
                pendingWriteIdm = ""
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
            pendingWriteIdm = ""
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

    private fun hexStringToByteArray(hex: String): ByteArray {
        val cleanHex = hex.uppercase().replace(" ", "")
        return ByteArray(cleanHex.length / 2) { i ->
            cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun writeIdm(nfcF: NfcF, currentIdm: ByteArray, newIdm: ByteArray): Boolean {
        val blockNum = 0x83.toByte()
        val data = newIdm + DEFAULT_PMM  // 8 bytes IDm + 8 bytes PMm = 16 bytes

        // コマンドデータの構築: [1, 0xFF, 0xFF, 1, 0x80, block_num] + data
        val cmdData = byteArrayOf(
            0x01,           // service count
            0xFF.toByte(),  // service code (little endian)
            0xFF.toByte(),
            0x01,           // block count
            0x80.toByte(),  // block list element (2-byte mode)
            blockNum        // block number
        ) + data

        // 完全なFeliCaコマンドの構築
        // [length] [command] [IDm] [service count] [service code] [block count] [block list] [data]
        val command = byteArrayOf(
            (1 + 1 + currentIdm.size + cmdData.size).toByte(),  // length
            COMMAND_WRITE                                        // command code
        ) + currentIdm + cmdData

        Log.d(TAG, "Sending command: ${command.toHexString()}")

        return try {
            val response = nfcF.transceive(command)
            Log.d(TAG, "Response: ${response.toHexString()}")

            // レスポンスチェック
            // 成功の場合: [length] [response_code] [IDm] [status_flag1] [status_flag2]
            if (response.size >= 11 && response[1] == 0x09.toByte()) {
                val statusFlag1 = response[9]
                val statusFlag2 = response[10]
                statusFlag1 == 0x00.toByte() && statusFlag2 == 0x00.toByte()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to tag", e)
            false
        }
    }

    private fun ByteArray.toHexString(): String {
        return this.joinToString("") { "%02X".format(it) }
    }
}

@Composable
fun NFCWriterScreen(
    statusMessage: String,
    currentIdm: String,
    inputIdm: String,
    onInputIdmChange: (String) -> Unit,
    onWriteClick: () -> Unit,
    isWriteMode: Boolean,
    debugInfo: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SiliCa Tag Writer",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // デバッグ情報
                Text(
                    text = debugInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // ステータス表示
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

                Spacer(modifier = Modifier.height(24.dp))

                // IDm入力フィールド
                OutlinedTextField(
                    value = inputIdm,
                    onValueChange = { newValue ->
                        // 16進数のみ受け付ける
                        if (newValue.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) {
                            onInputIdmChange(newValue.uppercase())
                        }
                    },
                    label = { Text("新しいIDm (16桁16進数)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isWriteMode,
                    supportingText = {
                        Text("例: DEADBEEF01010101 (8バイト)")
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 書き込みボタン
                Button(
                    onClick = onWriteClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWriteMode && inputIdm.length == 16
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
            }
        }
    }
}