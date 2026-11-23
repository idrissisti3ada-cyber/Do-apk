package com.example

import com.example.data.preferences.MonitorPreferences
import com.example.domain.AccessKeyManager
import com.example.domain.KeyValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessKeyManagerTest {

    @Test
    fun `admin key works every time regardless of used keys`() {
        val used = setOf("00000E10AABBCCDDEE", "112233445566778899")
        assertEquals(
            KeyValidationResult.AdminSuccess,
            AccessKeyManager.validateKey("Idriss.2008", emptySet())
        )
        assertEquals(
            KeyValidationResult.AdminSuccess,
            AccessKeyManager.validateKey("  Idriss.2008  ", used)
        )
        assertEquals(
            KeyValidationResult.AdminSuccess,
            AccessKeyManager.validateKey(AccessKeyManager.ADMIN_KEY, used)
        )
    }

    @Test
    fun `timed user key validates once then rejects reuse`() {
        val record = AccessKeyManager.generateUserKey(durationSeconds = 3_600L, label = "Ali")
        assertTrue(record.key.startsWith("MCH-"))
        assertEquals(3_600L, record.durationSeconds)

        val firstTry = AccessKeyManager.validateKey(record.key, emptySet())
        assertTrue(firstTry is KeyValidationResult.UserSuccess)
        val parsed = (firstTry as KeyValidationResult.UserSuccess).parsed
        assertEquals(record.keyId, parsed.keyId)
        assertEquals(3_600L, parsed.durationSeconds)
        assertFalse(parsed.isUnlimited)

        val secondTry = AccessKeyManager.validateKey(record.key, setOf(record.keyId))
        assertEquals(KeyValidationResult.AlreadyUsed, secondTry)
    }

    @Test
    fun `unlimited user key validates once then rejects reuse`() {
        val record = AccessKeyManager.generateUserKey(durationSeconds = 0L, label = "VIP")
        val firstTry = AccessKeyManager.validateKey(record.key, emptySet())
        assertTrue(firstTry is KeyValidationResult.UserSuccess)
        val parsed = (firstTry as KeyValidationResult.UserSuccess).parsed
        assertTrue(parsed.isUnlimited)
        assertEquals(0L, parsed.durationSeconds)

        val secondTry = AccessKeyManager.validateKey(record.key, setOf(record.keyId))
        assertEquals(KeyValidationResult.AlreadyUsed, secondTry)
    }

    @Test
    fun `user key validation handles lowercase and spaces while rejecting tampered keys`() {
        val record = AccessKeyManager.generateUserKey(durationSeconds = 900L)
        val messyInput = "  ${record.key.lowercase().replace("-", " ")}  "
        val valid = AccessKeyManager.validateKey(messyInput, emptySet())
        assertTrue(valid is KeyValidationResult.UserSuccess)

        val lastChar = record.key.last()
        val replacement = if (lastChar == 'A') 'B' else 'A'
        val tampered = record.key.dropLast(1) + replacement
        assertEquals(
            KeyValidationResult.InvalidFormat,
            AccessKeyManager.validateKey(tampered, emptySet())
        )
        assertEquals(
            KeyValidationResult.InvalidFormat,
            AccessKeyManager.validateKey("idriss.2008", emptySet())
        )
        assertEquals(
            KeyValidationResult.InvalidFormat,
            AccessKeyManager.validateKey("MCH-INVALID-KEY", emptySet())
        )
    }

    @Test
    fun `monitor preferences enforces expiry for timed user keys and never expires admin or unlimited`() {
        val locked = MonitorPreferences(isUnlocked = false)
        assertFalse(locked.hasValidAccess(nowMillis = 1_000L))

        val admin = MonitorPreferences(isUnlocked = true, isAdmin = true, expiresAtMillis = null)
        assertTrue(admin.hasValidAccess(nowMillis = Long.MAX_VALUE / 2))
        assertNull(admin.remainingMillis(nowMillis = 1_000L))

        val unlimited = MonitorPreferences(isUnlocked = true, isAdmin = false, isUnlimitedLicense = true)
        assertTrue(unlimited.hasValidAccess(nowMillis = Long.MAX_VALUE / 2))
        assertNull(unlimited.remainingMillis(nowMillis = 1_000L))

        val timed = MonitorPreferences(
            isUnlocked = true,
            isAdmin = false,
            isUnlimitedLicense = false,
            expiresAtMillis = 10_000L
        )
        assertTrue(timed.hasValidAccess(nowMillis = 9_999L))
        assertEquals(1L, timed.remainingMillis(nowMillis = 9_999L))
        assertFalse(timed.hasValidAccess(nowMillis = 10_000L))
        assertFalse(timed.hasValidAccess(nowMillis = 12_000L))
        assertEquals(0L, timed.remainingMillis(nowMillis = 12_000L))
    }
}
