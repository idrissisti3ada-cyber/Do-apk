package com.example.domain

enum class AppCaseMode(
    val id: String,
    val displayName: String,
    val defaultUrl: String
) {
    MYCODE_G7F(
        id = "mycode_g7f",
        displayName = "MyCodeG7f",
        defaultUrl = "https://shop2game.com"
    ),
    GARENA_CODE(
        id = "garena_code",
        displayName = "garenaCode",
        defaultUrl = "https://reward.ff.garena.com"
    );

    companion object {
        fun fromId(id: String?): AppCaseMode {
            return entries.firstOrNull { it.id == id } ?: MYCODE_G7F
        }
    }
}

enum class SourceApp(
    val displayName: String,
    val packages: Set<String>
) {
    WHATSAPP(
        displayName = "WhatsApp",
        packages = setOf("com.whatsapp", "com.whatsapp.w4b")
    ),
    INSTAGRAM(
        displayName = "Instagram",
        packages = setOf("com.instagram.android")
    ),
    OCR(
        displayName = "Screen OCR",
        packages = emptySet()
    );

    companion object {
        fun fromPackageName(packageName: String): SourceApp? {
            return entries.firstOrNull { packageName in it.packages }
        }
    }
}

object CodeExtractor {
    private val linePrefixRegex = Regex("^(\\d{1,3})\\s*([.\\-)])\\s*(.+)$")
    private val tokenPrefixRegex = Regex("^(\\d{1,3})([.\\-)])(.+)$")
    private val segmentLabelPrefixRegex = Regex("^\\s*\\d{1,3}\\s*[:.\\-)]\\s*(.+)$")
    private val standaloneLabelPrefixRegex = Regex("^\\s*\\d{1,3}\\s*[:.\\-)]\\s*$")
    private val timestampOnlyRegex = Regex("^\\d{1,2}:\\d{2}(?:\\s?[APap][Mm])?$")
    private val urlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val proseWordRegex = Regex("\\b[A-Za-z]*[a-z][A-Za-z]*\\b")
    private val whitespaceRegex = Regex("\\s+")

    // Numeric extractor regexes (MyCodeG7f: 15-16 digits)
    private val numericLineWordSplitRegex = Regex("[^0-9\\s]+")
    private val numericMultiSpaceRegex = Regex("\\s+")
    private val numericSeparatorBreakRegex = Regex("[A-Za-z\\p{L}]+")

    fun extractCodes(
        text: CharSequence?,
        mode: AppCaseMode = AppCaseMode.GARENA_CODE
    ): List<String> {
        return when (mode) {
            AppCaseMode.MYCODE_G7F -> extractNumericCodes(text)
            AppCaseMode.GARENA_CODE -> extractGarenaCodes(text)
        }
    }

    /**
     * MyCodeG7f mode:
     * Extracts and cleans 15-16 digit numeric codes, stripping letters, emojis, dots, dashes,
     * and numbering prefixes like 1. / 5- while merging multiline chunks into a single code
     * and keeping separate codes distinct.
     */
    fun extractNumericCodes(text: CharSequence?): List<String> {
        if (text.isNullOrBlank() || text.length < 15) return emptyList()

        // Fast zero-allocation pre-check: must have at least 15 digits
        var digitTotal = 0
        for (i in 0 until text.length) {
            if (text[i] in '0'..'9') {
                digitTotal++
                if (digitTotal >= 15) break
            }
        }
        if (digitTotal < 15) return emptyList()

        val found = LinkedHashSet<String>()
        val multilineBuffer = StringBuilder(20)

        fun flushMultilineIfValid() {
            if (multilineBuffer.length in 15..16) {
                found.add(multilineBuffer.toString())
            }
            multilineBuffer.clear()
        }

        val lines = text.toString().lines()
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                flushMultilineIfValid()
                continue
            }

            val lineWithoutUrls = urlRegex.replace(trimmed, " ").trim()
            if (lineWithoutUrls.isEmpty() || timestampOnlyRegex.matches(lineWithoutUrls)) {
                flushMultilineIfValid()
                continue
            }

            // 1. Check if this line itself contains one or more complete 15-16 digit codes
            val completeOnLine = extractNumericCodesFromLine(lineWithoutUrls)
            if (completeOnLine.isNotEmpty()) {
                flushMultilineIfValid()
                found.addAll(completeOnLine)
                continue
            }

            // 2. Otherwise inspect numeric chunk on this line for multiline assembly
            val strippedLine = stripNumericLinePrefixIfApplicable(lineWithoutUrls)
            val lineDigits = digitsOnly(strippedLine)
            if (lineDigits.isEmpty()) {
                flushMultilineIfValid()
                continue
            }

            // If line has prose/too many digits, don't glue unrelated numbers
            if (lineDigits.length > 16) {
                flushMultilineIfValid()
                continue
            }

            multilineBuffer.append(lineDigits)
            if (multilineBuffer.length in 15..16) {
                flushMultilineIfValid()
            } else if (multilineBuffer.length > 16) {
                multilineBuffer.clear()
                multilineBuffer.append(lineDigits)
            }
        }

        flushMultilineIfValid()
        return found.toList()
    }

    private fun extractNumericCodesFromLine(rawLine: String): List<String> {
        val results = LinkedHashSet<String>()
        val lineNoPrefix = stripNumericLinePrefixIfApplicable(rawLine)

        // Pass A: If whole line (after stripping numbering prefix) collapses to 15-16 digits
        // Handles "12ن3k45.67😯8@90sh9😝87;65", "1234 5678 9012 3456", "987-654-321-098-765!"
        val collapsedAll = digitsOnly(lineNoPrefix)
        val hasMultipleLongProseWords = proseWordRegex.findAll(lineNoPrefix)
            .count { it.value.length >= 4 } >= 2

        if (!hasMultipleLongProseWords && isValidNumericCode(collapsedAll)) {
            results.add(collapsedAll)
            return results.toList()
        }

        // Pass B: Split on whitespace-separated tokens first
        val tokens = lineNoPrefix.split(whitespaceRegex)
        for (token in tokens) {
            if (token.isBlank()) continue
            val stripped = stripNumericTokenPrefixIfApplicable(token)
            val d = digitsOnly(stripped)
            if (isValidNumericCode(d)) {
                results.add(d)
            }
        }

        // Pass C: Split around alphabetic prose words so "Use code 1234 5678 9012 3456 now" or
        // "ID 1111 - 2222 - 3333 - 4444 valid" or "Code 1: 1234... and Code 2: 9876..." work cleanly
        val segments = numericSeparatorBreakRegex.split(lineNoPrefix)
        for (seg in segments) {
            val cleanedSeg = digitsOnly(stripNumericLabelPrefix(seg))
            if (isValidNumericCode(cleanedSeg)) {
                results.add(cleanedSeg)
            }
        }

        return results.toList()
    }

    private fun stripNumericLinePrefixIfApplicable(line: String): String {
        val match = linePrefixRegex.matchEntire(line) ?: return line
        val sep = match.groupValues[2]
        val rest = match.groupValues[3]
        val restDigits = digitsOnly(rest)
        val fullDigits = digitsOnly(line)

        if (isValidNumericCode(restDigits)) return rest
        if ((sep == "." || sep == ")") && restDigits.isNotEmpty() && fullDigits.length !in 15..16) {
            return rest
        }
        if (sep == "-" && line.substring(match.groupValues[1].length + 1).startsWith(" ") &&
            restDigits.isNotEmpty() && fullDigits.length !in 15..16
        ) {
            return rest
        }
        return line
    }

    private fun stripNumericTokenPrefixIfApplicable(token: String): String {
        val match = tokenPrefixRegex.matchEntire(token) ?: return token
        val rest = match.groupValues[3]
        return if (isValidNumericCode(digitsOnly(rest))) rest else token
    }

    private fun stripNumericLabelPrefix(segment: String): String {
        val trimmed = segment.trim()
        if (trimmed.isEmpty() || standaloneLabelPrefixRegex.matches(trimmed)) return ""
        val rawDigits = digitsOnly(trimmed)
        if (isValidNumericCode(rawDigits)) return rawDigits
        val match = segmentLabelPrefixRegex.matchEntire(trimmed)
        if (match != null) {
            val restDigits = digitsOnly(match.groupValues[1])
            if (restDigits.isNotEmpty()) return restDigits
        }
        return rawDigits
    }

    private fun digitsOnly(input: CharSequence): String {
        val sb = StringBuilder(input.length)
        for (i in 0 until input.length) {
            val ch = input[i]
            if (ch in '0'..'9') sb.append(ch)
        }
        return sb.toString()
    }

    fun isValidNumericCode(code: String): Boolean {
        if (code.length !in 15..16) return false
        for (i in 0 until code.length) {
            if (code[i] !in '0'..'9') return false
        }
        return true
    }

    /**
     * garenaCode mode:
     * Extracts and cleans all valid 12-character Garena redeem codes (uppercase A-Z and digits 0-9):
     * 1. Keeps only uppercase A-Z and 0-9, stripping points, spaces, emojis, lowercase noise, and symbols like ;#(' .
     * 2. Strips numbering prefixes like 1. / 5- / 1) before codes.
     * 3. Merges multiline code chunks into one 12-character code.
     * 4. Ignores phone numbers (digits-only) and normal uppercase English words (letters-only).
     * 5. Keeps multiple valid codes separate (never merges separate codes together).
     */
    fun extractGarenaCodes(text: CharSequence?): List<String> {
        if (text.isNullOrBlank() || text.length < 12) return emptyList()

        // Fast zero-allocation pre-check: must have at least 1 uppercase A-Z, 1 digit 0-9, and 12 total [A-Z0-9]
        var upperCount = 0
        var digitCount = 0
        for (i in 0 until text.length) {
            val ch = text[i]
            if (ch in 'A'..'Z') {
                upperCount++
            } else if (ch in '0'..'9') {
                digitCount++
            }
        }
        if (upperCount == 0 || digitCount == 0 || upperCount + digitCount < 12) {
            return emptyList()
        }

        val found = LinkedHashSet<String>()
        val multilineBuffer = StringBuilder(16)

        fun flushMultilineIfValid() {
            if (multilineBuffer.length == 12) {
                val candidate = multilineBuffer.toString()
                if (isValidGarenaCode(candidate)) {
                    found.add(candidate)
                }
            }
            multilineBuffer.clear()
        }

        val lines = text.toString().lines()
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                flushMultilineIfValid()
                continue
            }

            val lineWithoutUrls = urlRegex.replace(trimmed, " ").trim()
            if (lineWithoutUrls.isEmpty() || timestampOnlyRegex.matches(lineWithoutUrls)) {
                flushMultilineIfValid()
                continue
            }

            // 1. Check if this line already contains one or more complete 12-char codes
            val lineCompleteCodes = extractCompleteGarenaCodesFromLine(lineWithoutUrls)
            if (lineCompleteCodes.isNotEmpty()) {
                flushMultilineIfValid()
                found.addAll(lineCompleteCodes)
                continue
            }

            // 2. Otherwise inspect line chunks for multiline assembly
            val lineNoPrefix = stripLinePrefixIfApplicable(lineWithoutUrls)
            val segments = proseWordRegex.split(lineNoPrefix)
            val lineChunks = ArrayList<String>(2)
            for (seg in segments) {
                val cleanedSeg = cleanGarenaSegment(seg)
                if (cleanedSeg.isNotEmpty()) {
                    lineChunks.add(cleanedSeg)
                }
            }

            if (lineChunks.isEmpty()) {
                flushMultilineIfValid()
                continue
            }

            val combinedLineChunk = lineChunks.joinToString("")
            if (combinedLineChunk.length > 12) {
                flushMultilineIfValid()
                continue
            }

            multilineBuffer.append(combinedLineChunk)
            if (multilineBuffer.length == 12) {
                flushMultilineIfValid()
            } else if (multilineBuffer.length > 12) {
                multilineBuffer.clear()
                multilineBuffer.append(combinedLineChunk)
            }
        }

        flushMultilineIfValid()
        return found.toList()
    }

    private fun extractCompleteGarenaCodesFromLine(line: String): List<String> {
        val results = LinkedHashSet<String>()
        val lineWithoutPrefix = stripLinePrefixIfApplicable(line)

        val tokens = lineWithoutPrefix.split(whitespaceRegex)
        for (token in tokens) {
            if (token.isBlank()) continue
            val stripped = stripTokenPrefixIfApplicable(token)
            val cleaned = upperAlnumOnly(stripped)
            if (isValidGarenaCode(cleaned)) {
                results.add(cleaned)
            }
        }

        val segments = proseWordRegex.split(lineWithoutPrefix)
        for (seg in segments) {
            val cleaned = cleanGarenaSegment(seg)
            if (isValidGarenaCode(cleaned)) {
                results.add(cleaned)
            }
        }

        return results.toList()
    }

    private fun cleanGarenaSegment(segment: String): String {
        val trimmed = segment.trim()
        if (trimmed.isEmpty() || standaloneLabelPrefixRegex.matches(trimmed)) return ""
        val rawAlnum = upperAlnumOnly(trimmed)
        if (rawAlnum.length == 12 && isValidGarenaCode(rawAlnum)) {
            return rawAlnum
        }
        val match = segmentLabelPrefixRegex.matchEntire(trimmed)
        if (match != null) {
            val restAlnum = upperAlnumOnly(match.groupValues[1])
            if (restAlnum.isNotEmpty()) {
                return restAlnum
            }
        }
        return rawAlnum
    }

    private fun stripLinePrefixIfApplicable(line: String): String {
        val match = linePrefixRegex.matchEntire(line) ?: return line
        val sep = match.groupValues[2]
        val rest = match.groupValues[3]
        val restCleaned = upperAlnumOnly(rest)
        val fullCleaned = upperAlnumOnly(line)

        if (isValidGarenaCode(restCleaned)) {
            return rest
        }
        if ((sep == "." || sep == ")") && restCleaned.isNotEmpty() && fullCleaned.length != 12) {
            return rest
        }
        if (sep == "-" && line.substring(match.groupValues[1].length + 1).startsWith(" ") &&
            restCleaned.isNotEmpty() && fullCleaned.length != 12
        ) {
            return rest
        }
        return line
    }

    private fun stripTokenPrefixIfApplicable(token: String): String {
        val match = tokenPrefixRegex.matchEntire(token) ?: return token
        val rest = match.groupValues[3]
        val restCleaned = upperAlnumOnly(rest)
        return if (isValidGarenaCode(restCleaned)) rest else token
    }

    private fun upperAlnumOnly(input: CharSequence): String {
        val sb = StringBuilder(input.length)
        for (i in 0 until input.length) {
            val ch = input[i]
            if (ch in 'A'..'Z' || ch in '0'..'9') {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun isValidGarenaCode(code: String): Boolean {
        if (code.length != 12) return false
        var hasUpper = false
        var hasDigit = false
        for (i in 0 until code.length) {
            val ch = code[i]
            when (ch) {
                in 'A'..'Z' -> hasUpper = true
                in '0'..'9' -> hasDigit = true
                else -> return false
            }
        }
        return hasUpper && hasDigit
    }

    fun formatForDisplay(code: String): String {
        return code.chunked(4).joinToString(" ")
    }
}
