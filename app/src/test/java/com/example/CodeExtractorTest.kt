package com.example

import com.example.domain.AppCaseMode
import com.example.domain.CodeExtractor
import com.example.domain.SourceApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeExtractorTest {

    // ── garenaCode (12-char uppercase A-Z & 0-9) ─────────────────────────────────────────

    @Test
    fun `garenaCode extracts clean 12-character uppercase alphanumeric code`() {
        assertEquals(
            listOf("SH19JD73HDOS"),
            CodeExtractor.extractCodes("SH19JD73HDOS", AppCaseMode.GARENA_CODE)
        )
    }

    @Test
    fun `garenaCode cleans dots spaces lowercase emojis and symbols like semicolon hash paren apostrophe`() {
        assertEquals(
            listOf("SH19JD73HDOS"),
            CodeExtractor.extractCodes("SH.19;JD#73(HD'OS", AppCaseMode.GARENA_CODE)
        )
        assertEquals(
            listOf("SH19JD73HDOS"),
            CodeExtractor.extractCodes("S H 1 9 J D 7 3 H D O S", AppCaseMode.GARENA_CODE)
        )
        assertEquals(
            listOf("SH19JD73HDOS"),
            CodeExtractor.extractCodes("S.H19نkJD73😯HD;OS#'", AppCaseMode.GARENA_CODE)
        )
        assertEquals(
            listOf("SH19JD73HDOS"),
            CodeExtractor.extractCodes("Code 1: S.H;1#9 J(D'73 H.D'OS", AppCaseMode.GARENA_CODE)
        )
    }

    @Test
    fun `garenaCode merges multiline chunks and keeps separate codes distinct`() {
        val message = """
            Hello team, visit https://reward.ff.garena.com at 14:32:
            1.SH19JD73HDOS
            5-FF11NJN5YS3E
            Code 3:
            AB12
            CD34 #' abc
            EF56 تيتي 😝
            Ignore short AB12CD34EF5 and long SH19JD73HDOSX.
        """.trimIndent()

        assertEquals(
            listOf("SH19JD73HDOS", "FF11NJN5YS3E", "AB12CD34EF56"),
            CodeExtractor.extractCodes(message, AppCaseMode.GARENA_CODE)
        )
    }

    @Test
    fun `garenaCode rejects phone numbers plain uppercase words and wrong lengths`() {
        assertEquals(emptyList<String>(), CodeExtractor.extractCodes("Call +212 612-345-678 now", AppCaseMode.GARENA_CODE))
        assertEquals(emptyList<String>(), CodeExtractor.extractCodes("CONFIRMATION ANNOUNCEMENT", AppCaseMode.GARENA_CODE))
        assertFalse(CodeExtractor.isValidGarenaCode("123456789012"))
        assertFalse(CodeExtractor.isValidGarenaCode("ABCDEFGHIJKL"))
        assertTrue(CodeExtractor.isValidGarenaCode("SH19JD73HDOS"))
    }

    // ── MyCodeG7f (15-16 digit numeric codes) ────────────────────────────────────────────

    @Test
    fun `mycodeG7f removes numbering prefixes before dot or dash`() {
        assertEquals(
            listOf("1234567890123456"),
            CodeExtractor.extractCodes("1.1234567890123456", AppCaseMode.MYCODE_G7F)
        )
        assertEquals(
            listOf("1029384765098765"),
            CodeExtractor.extractCodes("5-1029384765098765", AppCaseMode.MYCODE_G7F)
        )
    }

    @Test
    fun `mycodeG7f keeps digits only by stripping letters emojis and symbols`() {
        assertEquals(
            listOf("123456789098765"),
            CodeExtractor.extractCodes("12ن3k45.67😯8@90sh9😝87;65", AppCaseMode.MYCODE_G7F)
        )
        assertEquals(
            listOf("1234567890098765"),
            CodeExtractor.extractCodes("12ن3k45.67😯8@90sh09😝87;65", AppCaseMode.MYCODE_G7F)
        )
    }

    @Test
    fun `mycodeG7f merges multiline code chunks and extracts 25+ codes without dropping`() {
        val multiline = """
            1234
            5678 djd
            9012 تيتي
            3456 😝
        """.trimIndent()
        assertEquals(listOf("1234567890123456"), CodeExtractor.extractCodes(multiline, AppCaseMode.MYCODE_G7F))

        val batch = (1..25).map { idx ->
            "Code $idx: ${(1000000000000000L + idx)}"
        }.joinToString("\n")
        val extracted = CodeExtractor.extractCodes(batch, AppCaseMode.MYCODE_G7F)
        assertEquals(25, extracted.size)
        assertEquals("1000000000000025", extracted.last())
    }

    @Test
    fun `mycodeG7f extracts codes separated by spaces or dashes and ignores invalid lengths`() {
        assertEquals(
            listOf("1234567890123456"),
            CodeExtractor.extractCodes("Use code 1234 5678 9012 3456 now", AppCaseMode.MYCODE_G7F)
        )
        assertEquals(
            listOf("987654321098765"),
            CodeExtractor.extractCodes("Code: 987-654-321-098-765!", AppCaseMode.MYCODE_G7F)
        )
        assertEquals(
            listOf("1111222233334444"),
            CodeExtractor.extractCodes("ID 1111 - 2222 - 3333 - 4444 valid", AppCaseMode.MYCODE_G7F)
        )
        assertEquals(emptyList<String>(), CodeExtractor.extractCodes("PIN 12345678901234", AppCaseMode.MYCODE_G7F))
        assertEquals(emptyList<String>(), CodeExtractor.extractCodes("Ref 12345678901234567", AppCaseMode.MYCODE_G7F))
    }

    @Test
    fun `maps whatsapp personal business and instagram packages`() {
        assertEquals(SourceApp.WHATSAPP, SourceApp.fromPackageName("com.whatsapp"))
        assertEquals(SourceApp.WHATSAPP, SourceApp.fromPackageName("com.whatsapp.w4b"))
        assertEquals(SourceApp.INSTAGRAM, SourceApp.fromPackageName("com.instagram.android"))
        assertNull(SourceApp.fromPackageName("com.other.app"))
    }
}
