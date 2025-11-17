package jp.muo.silicarw.util

fun tryAcceptHexInput(newValue: String, maxLength: Int? = null): String? {
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
