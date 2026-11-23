package com.example.domain

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ParsedUserKey(
    val keyId: String,
    val durationSeconds: Long,
    val isUnlimited: Boolean
)

data class GeneratedKeyRecord(
    val key: String,
    val keyId: String,
    val label: String,
    val durationSeconds: Long,
    val createdAtMillis: Long
)

sealed class KeyValidationResult {
    data object AdminSuccess : KeyValidationResult()
    data class UserSuccess(val parsed: ParsedUserKey) : KeyValidationResult()
    data object AlreadyUsed : KeyValidationResult()
    data object InvalidFormat : KeyValidationResult()
}

object AccessKeyManager {
    const val ADMIN_KEY = "Idriss.2008"

    private const val KEY_PREFIX = "MCH"
    private const val VERSION_BYTE: Byte = 0x01
    private const val PAYLOAD_BYTES = 10 // 1 byte version + 4 bytes durationSec + 5 bytes nonce
    private const val TAG_BYTES = 5      // 40-bit HMAC-SHA256 tag
    private const val TOTAL_BYTES = PAYLOAD_BYTES + TAG_BYTES // 15 bytes = 24 Base32 chars
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val HMAC_SECRET = "MyCodeHub::Idriss.2008::G7F::Garena::v1"

    private val secureRandom = SecureRandom()

    fun generateUserKey(
        durationSeconds: Long,
        label: String = "",
        nowMillis: Long = System.currentTimeMillis()
    ): GeneratedKeyRecord {
        require(durationSeconds >= 0L && durationSeconds <= 0xFFFFFFFFL) {
            "Duration out of supported range"
        }

        val payload = ByteArray(TOTAL_BYTES)
        payload[0] = VERSION_BYTE

        // Unsigned 32-bit duration in seconds (0 = unlimited)
        val durBuf = ByteBuffer.allocate(4).putInt(durationSeconds.toInt()).array()
        System.arraycopy(durBuf, 0, payload, 1, 4)

        // 5-byte random nonce so every generated key is globally unique
        val nonce = ByteArray(5)
        secureRandom.nextBytes(nonce)
        System.arraycopy(nonce, 0, payload, 5, 5)

        val tag = computeHmacTag(payload, PAYLOAD_BYTES)
        System.arraycopy(tag, 0, payload, PAYLOAD_BYTES, TAG_BYTES)

        val encoded = encodeBase32(payload) // 24 chars
        val formatted = buildString {
            append(KEY_PREFIX)
            for (i in encoded.indices step 4) {
                append('-')
                append(encoded, i, minOf(i + 4, encoded.length))
            }
        }
        val keyId = bytesToHex(payload, 1, 9) // duration + nonce uniquely identifies key

        return GeneratedKeyRecord(
            key = formatted,
            keyId = keyId,
            label = label.trim(),
            durationSeconds = durationSeconds,
            createdAtMillis = nowMillis
        )
    }

    fun validateKey(rawInput: String, usedKeyIds: Set<String>): KeyValidationResult {
        val trimmed = rawInput.trim()
        if (trimmed == ADMIN_KEY) {
            return KeyValidationResult.AdminSuccess
        }

        val parsed = parseAndVerifyUserKey(trimmed) ?: return KeyValidationResult.InvalidFormat
        if (parsed.keyId in usedKeyIds) {
            return KeyValidationResult.AlreadyUsed
        }
        return KeyValidationResult.UserSuccess(parsed)
    }

    fun parseAndVerifyUserKey(rawInput: String): ParsedUserKey? {
        val normalized = rawInput.trim()
            .replace("-", "")
            .replace(" ", "")
            .replace("\n", "")
            .uppercase()

        if (!normalized.startsWith(KEY_PREFIX)) return null
        val body = normalized.removePrefix(KEY_PREFIX)
        if (body.length != 24) return null

        val bytes = decodeBase32(body) ?: return null
        if (bytes.size != TOTAL_BYTES) return null
        if (bytes[0] != VERSION_BYTE) return null

        val expectedTag = computeHmacTag(bytes, PAYLOAD_BYTES)
        val actualTag = bytes.copyOfRange(PAYLOAD_BYTES, TOTAL_BYTES)
        if (!MessageDigest.isEqual(expectedTag, actualTag)) {
            return null
        }

        val durationSeconds = ByteBuffer.wrap(bytes, 1, 4).int.toLong() and 0xFFFFFFFFL
        val keyId = bytesToHex(bytes, 1, 9)

        return ParsedUserKey(
            keyId = keyId,
            durationSeconds = durationSeconds,
            isUnlimited = durationSeconds == 0L
        )
    }

    fun formatDurationLabel(durationSeconds: Long): String {
        if (durationSeconds <= 0L) return "Unlimited"
        val days = durationSeconds / 86_400L
        val hours = (durationSeconds % 86_400L) / 3_600L
        val minutes = (durationSeconds % 3_600L) / 60L
        val seconds = durationSeconds % 60L

        return buildList {
            if (days > 0L) add("${days}d")
            if (hours > 0L) add("${hours}h")
            if (minutes > 0L) add("${minutes}m")
            if (seconds > 0L && days == 0L && hours == 0L) add("${seconds}s")
        }.joinToString(" ").ifEmpty { "${durationSeconds}s" }
    }

    fun formatRemainingMillis(remainingMillis: Long): String {
        if (remainingMillis <= 0L) return "Expired"
        val totalSeconds = (remainingMillis + 999L) / 1000L
        val days = totalSeconds / 86_400L
        val hours = (totalSeconds % 86_400L) / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L

        return when {
            days > 0L -> "${days}d ${hours}h ${minutes}m"
            hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun computeHmacTag(data: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(data, 0, length)
        val full = mac.doFinal()
        return full.copyOfRange(0, TAG_BYTES)
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                out.append(BASE32_ALPHABET[(buffer ushr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return out.toString()
    }

    private fun decodeBase32(encoded: String): ByteArray? {
        val out = ArrayList<Byte>((encoded.length * 5) / 8)
        var buffer = 0
        var bitsLeft = 0
        for (ch in encoded) {
            val valIdx = BASE32_ALPHABET.indexOf(ch)
            if (valIdx == -1) return null
            buffer = (buffer shl 5) or valIdx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer ushr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray, offset: Int, length: Int): String {
        val hexChars = "0123456789ABCDEF"
        val sb = StringBuilder(length * 2)
        for (i in offset until (offset + length)) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }
}
