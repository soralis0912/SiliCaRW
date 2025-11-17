package jp.muo.silicarw.util

fun ByteArray.toHexString(): String {
    return this.joinToString("") { "%02X".format(it) }
}

fun hexStringToByteArray(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
