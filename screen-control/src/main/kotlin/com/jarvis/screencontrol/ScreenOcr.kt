package com.jarvis.screencontrol

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Офлайн OCR (ML Kit Latin) поверх скриншота Accessibility.
 * Кириллица ограничена — для DoD Phase 3 хватает латиницы/цифр/смешанных UI;
 * полный rus — следующий слой (Tesseract tessdata).
 */
object ScreenOcr {

    data class Block(
        val text: String,
        val bounds: Rect,
    )

    private const val TAG = "Jarvis.OCR"
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun recognize(bitmap: Bitmap, timeoutMs: Long = 8_000L): List<Block> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latch = CountDownLatch(1)
        val out = AtomicReference<List<Block>>(emptyList())
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val blocks = mutableListOf<Block>()
                for (block in result.textBlocks) {
                    val box = block.boundingBox ?: continue
                    val t = block.text.trim()
                    if (t.isNotBlank()) blocks += Block(t, Rect(box))
                    for (line in block.lines) {
                        val lb = line.boundingBox ?: continue
                        val lt = line.text.trim()
                        if (lt.isNotBlank() && lt != t) blocks += Block(lt, Rect(lb))
                    }
                }
                out.set(blocks)
                latch.countDown()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "OCR failed: ${e.message}")
                latch.countDown()
            }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out.get()
    }

    fun recognizeScreen(timeoutMs: Long = 8_000L): List<Block> {
        val bmp = ScreenController.takeScreenshotBitmap() ?: return emptyList()
        return try {
            recognize(bmp, timeoutMs)
        } finally {
            bmp.recycle()
        }
    }

    fun findBest(query: String, blocks: List<Block>): Block? {
        val q = query.lowercase().trim()
        if (q.isBlank() || blocks.isEmpty()) return null
        val qKey = com.jarvis.core.nlp.Transliterator.compareKey(q)
        return blocks.mapNotNull { b ->
            val t = b.text.lowercase()
            val key = com.jarvis.core.nlp.Transliterator.compareKey(t)
            val score = when {
                t == q -> 100
                t.contains(q) || q.contains(t) -> 80
                key.contains(qKey) || qKey.contains(key) -> 70
                else -> 0
            }
            if (score > 0) b to score else null
        }.maxByOrNull { it.second }?.first
    }
}
