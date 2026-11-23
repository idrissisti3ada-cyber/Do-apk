package com.example.service

import android.graphics.Bitmap
import com.example.domain.AppCaseMode
import com.example.domain.CodeExtractor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

object ScreenOcrProcessor {
    private const val MAX_OCR_WIDTH = 960

    private val warmupExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var recognizerInstance: TextRecognizer? = null

    private fun getRecognizer(): TextRecognizer {
        return recognizerInstance ?: synchronized(this) {
            recognizerInstance ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
                recognizerInstance = it
            }
        }
    }

    /**
     * Pre-initializes the bundled on-device ML Kit TextRecognizer off the main thread
     * so double-tap screen OCR runs with zero cold-start delay.
     */
    fun warmup() {
        if (recognizerInstance != null) return
        warmupExecutor.execute {
            runCatching { getRecognizer() }
        }
    }

    fun extractCodesFromBitmap(
        sourceBitmap: Bitmap,
        mode: AppCaseMode = AppCaseMode.GARENA_CODE,
        recycleSource: Boolean = true,
        onResult: (List<String>) -> Unit
    ) {
        if (sourceBitmap.isRecycled) {
            onResult(emptyList())
            return
        }

        val ocrBitmap = try {
            if (sourceBitmap.width > MAX_OCR_WIDTH) {
                val ratio = MAX_OCR_WIDTH.toFloat() / sourceBitmap.width.toFloat()
                val targetH = (sourceBitmap.height * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(sourceBitmap, MAX_OCR_WIDTH, targetH, false)
                if (recycleSource && scaled !== sourceBitmap && !sourceBitmap.isRecycled) {
                    sourceBitmap.recycle()
                }
                scaled
            } else {
                sourceBitmap
            }
        } catch (_: Throwable) {
            if (recycleSource && !sourceBitmap.isRecycled) sourceBitmap.recycle()
            onResult(emptyList())
            return
        }

        val image = try {
            InputImage.fromBitmap(ocrBitmap, 0)
        } catch (_: Throwable) {
            if (recycleSource && !ocrBitmap.isRecycled) ocrBitmap.recycle()
            onResult(emptyList())
            return
        }

        try {
            getRecognizer().process(image)
                .addOnSuccessListener { visionText ->
                    val found = LinkedHashSet<String>()
                    for (block in visionText.textBlocks) {
                        val blockText = block.text
                        if (blockText.isNotBlank()) {
                            found.addAll(CodeExtractor.extractCodes(blockText, mode))
                        }
                    }
                    if (found.isEmpty() && visionText.text.isNotBlank()) {
                        found.addAll(CodeExtractor.extractCodes(visionText.text, mode))
                    }
                    if (recycleSource && !ocrBitmap.isRecycled) {
                        ocrBitmap.recycle()
                    }
                    onResult(found.toList())
                }
                .addOnFailureListener {
                    if (recycleSource && !ocrBitmap.isRecycled) {
                        ocrBitmap.recycle()
                    }
                    onResult(emptyList())
                }
        } catch (_: Throwable) {
            if (recycleSource && !ocrBitmap.isRecycled) {
                ocrBitmap.recycle()
            }
            onResult(emptyList())
        }
    }
}
