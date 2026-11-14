package com.lunacode.photonnews.data

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.LongBuffer
import java.util.LinkedHashMap
import kotlin.math.sqrt

/**
 * Local multilingual sentence embeddings using intfloat/multilingual-e5-small.
 * The model is optional and is downloaded only after an explicit user action.
 */
class MultilingualE5Embedder(files: E5ModelFiles) : Closeable {
    companion object {
        private const val MAX_TOKENS = 512
        private const val EMBEDDING_CACHE_SIZE = 256
    }

    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        setInterOpNumThreads(1)
    }
    private val session = environment.createSession(files.model.absolutePath, sessionOptions)
    private val tokenizer = HuggingFaceTokenizer.newInstance(files.tokenizer.toPath())
    private val cache = object : LinkedHashMap<String, FloatArray>(EMBEDDING_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > EMBEDDING_CACHE_SIZE
    }

    @Synchronized
    fun embed(text: String): FloatArray {
        val normalized = text.trim().take(4_000)
        require(normalized.isNotBlank()) { "Текст для семантического анализа пуст" }
        cache[normalized]?.let { return it.copyOf() }

        // E5 uses task prefixes; news items are indexed as passages.
        val encoding = tokenizer.encode("passage: $normalized")
        val ids = encoding.ids.take(MAX_TOKENS).toLongArray()
        val mask = encoding.attentionMask.take(ids.size).toLongArray()
        val typeIds = encoding.typeIds.take(ids.size).toLongArray()
        require(ids.isNotEmpty() && ids.size == mask.size && ids.size == typeIds.size) {
            "Токенизатор вернул некорректную последовательность"
        }

        val shape = longArrayOf(1, ids.size.toLong())
        val inputIds = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
        val attentionMask = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape)
        val tokenTypeIds = OnnxTensor.createTensor(environment, LongBuffer.wrap(typeIds), shape)
        try {
            val inputs = mapOf(
                "input_ids" to inputIds,
                "attention_mask" to attentionMask,
                "token_type_ids" to tokenTypeIds
            )
            session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = result[0].value as Array<Array<FloatArray>>
                val tokens = hidden.firstOrNull() ?: error("Модель вернула пустое embedding-представление")
                val dimensions = tokens.firstOrNull()?.size ?: 0
                require(dimensions > 0 && tokens.size == ids.size) {
                    "Неожиданная форма выхода multilingual-e5-small"
                }
                val pooled = FloatArray(dimensions)
                var included = 0
                tokens.forEachIndexed { index, vector ->
                    if (mask[index] != 0L) {
                        for (dimension in vector.indices) pooled[dimension] += vector[dimension]
                        included++
                    }
                }
                require(included > 0) { "Модель не вернула токены для pooling" }
                for (dimension in pooled.indices) pooled[dimension] /= included
                val norm = sqrt(pooled.sumOf { (it * it).toDouble() }).toFloat()
                require(norm > 0f && norm.isFinite()) { "Некорректная норма embedding-вектора" }
                for (dimension in pooled.indices) pooled[dimension] /= norm
                cache[normalized] = pooled
                return pooled.copyOf()
            }
        } finally {
            inputIds.close()
            attentionMask.close()
            tokenTypeIds.close()
        }
    }

    @Synchronized
    fun similarity(first: String, second: String): Double =
        EmbeddingMath.cosineSimilarity(embed(first), embed(second))

    override fun close() {
        tokenizer.close()
        session.close()
        sessionOptions.close()
    }
}

object SemanticModelRegistry {
    @Volatile private var active: MultilingualE5Embedder? = null

    fun isReady(): Boolean = active != null

    @Synchronized
    fun install(files: E5ModelFiles) {
        val previous = active
        active = null
        previous?.close()
        active = MultilingualE5Embedder(files)
    }

    fun similarity(first: String, second: String): Double? =
        active?.let { runCatching { it.similarity(first, second) }.getOrNull() }

    @Synchronized
    fun shutdown() {
        active?.close()
        active = null
    }
}

object EmbeddingMath {
    fun cosineSimilarity(first: FloatArray, second: FloatArray): Double {
        if (first.isEmpty() || first.size != second.size) return 0.0
        val dot = first.indices.sumOf { first[it].toDouble() * second[it].toDouble() }
        val firstNorm = kotlin.math.sqrt(first.sumOf { (it * it).toDouble() })
        val secondNorm = kotlin.math.sqrt(second.sumOf { (it * it).toDouble() })
        if (firstNorm == 0.0 || secondNorm == 0.0) return 0.0
        return (dot / (firstNorm * secondNorm)).coerceIn(-1.0, 1.0)
    }
}
