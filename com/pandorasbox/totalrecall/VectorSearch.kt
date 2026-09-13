package com.pandorasbox.totalrecall

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

enum class ConfidenceLevel(val label: String, val colorHex: Long) {
    HIGH("Strong Match", 0xFF2E7D32),       // Dark Green
    MEDIUM("Possible Match", 0xFFE65100),   // Dark Orange
    LOW("Best Available", 0xFF616161)       // Neutral Gray
}

data class SearchResult(
    val mediaId: Long,
    val uriString: String,
    val similarityScore: Float,
    val confidenceLevel: ConfidenceLevel,
    val percentageMatch: Int,
    val explanation: String = ""
)

data class DuplicateGroup(
    val primaryImage: IndexedImage,
    val similarImages: List<IndexedImage>,
    val averageSimilarity: Float
)

data class MemoryInsights(
    val totalIndexedMemories: Int,
    val recent30DaysCount: Int,
    val mostActiveMonth: String,
    val nearDuplicateGroupsCount: Int
)

object VectorSearch {
    private const val TAG = "VectorSearch"

    const val HIGH_CONFIDENCE_THRESHOLD = 0.28f
    const val MEDIUM_CONFIDENCE_THRESHOLD = 0.22f
    const val MINIMUM_DISPLAY_THRESHOLD = 0.15f
    const val RELATIVE_SCORE_FACTOR = 0.65f
    const val MAX_RESULTS = 20

    const val NEAR_DUPLICATE_THRESHOLD = 0.92f

    fun getConfidenceLevel(score: Float): ConfidenceLevel {
        return when {
            score >= HIGH_CONFIDENCE_THRESHOLD -> ConfidenceLevel.HIGH
            score >= MEDIUM_CONFIDENCE_THRESHOLD -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }

    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0.0f
        
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in v1.indices) {
            val a = v1[i]
            val b = v2[i]
            if (a.isNaN() || b.isNaN()) return 0.0f
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        if (normA <= 0.0f || normB <= 0.0f) return 0.0f

        val sim = dotProduct / (sqrt(normA) * sqrt(normB))
        return if (sim.isNaN()) 0.0f else sim
    }

    fun computeMetadataScore(
        itemTimeSeconds: Long,
        timeFilter: TimeFilter,
        nowSeconds: Long = System.currentTimeMillis() / 1000L
    ): Float {
        if (timeFilter == TimeFilter.ALL || itemTimeSeconds <= 0L) return 0.5f

        val ageDays = maxOf(0f, (nowSeconds - itemTimeSeconds) / 86400.0f)

        return when (timeFilter) {
            TimeFilter.TODAY -> if (ageDays <= 1f) 1.0f else 0.1f
            TimeFilter.THIS_WEEK -> if (ageDays <= 7f) 1.0f else maxOf(0.1f, 1.0f - (ageDays - 7f) / 30f)
            TimeFilter.RECENT, TimeFilter.THIS_MONTH -> if (ageDays <= 30f) 1.0f else maxOf(0.1f, 1.0f - (ageDays - 30f) / 90f)
            TimeFilter.LAST_MONTH -> if (ageDays in 30f..60f) 1.0f else 0.2f
            TimeFilter.OLD -> if (ageDays >= 180f) 1.0f else maxOf(0.1f, ageDays / 180f)
            else -> 0.5f
        }
    }

    fun buildExplanation(
        level: ConfidenceLevel,
        scorePct: Int,
        queryText: String?,
        timeFilter: TimeFilter,
        isSimilarSearch: Boolean
    ): String {
        if (isSimilarSearch) {
            return "$scorePct% similarity to selected memory"
        }

        val q = queryText?.trim() ?: "search query"
        val baseMatch = when (level) {
            ConfidenceLevel.HIGH -> "Strong visual match for \"$q\""
            ConfidenceLevel.MEDIUM -> "Possible match for \"$q\""
            ConfidenceLevel.LOW -> "Related match for \"$q\""
        }

        return if (timeFilter != TimeFilter.ALL) {
            "$baseMatch · Filtered by date"
        } else {
            baseMatch
        }
    }

    fun searchTopK(
        queryEmbedding: FloatArray,
        indexMap: Map<Long, IndexedImage>,
        maxResults: Int = MAX_RESULTS,
        minThreshold: Float = MINIMUM_DISPLAY_THRESHOLD,
        excludeMediaId: Long? = null,
        structuredQuery: StructuredSearchQuery? = null,
        semanticWeight: Float = 0.85f,
        metadataWeight: Float = 0.15f
    ): List<SearchResult> {
        if (indexMap.isEmpty() || queryEmbedding.isEmpty()) {
            Log.w(TAG, "Index map or query embedding is empty.")
            return emptyList()
        }

        val timeFilter = structuredQuery?.timeFilter ?: TimeFilter.ALL
        val sortOrder = structuredQuery?.sortOrder ?: SortOrder.RELEVANCE
        val queryText = structuredQuery?.originalQuery

        val allResults = mutableListOf<SearchResult>()
        var validVectorsCount = 0

        for ((_, item) in indexMap) {
            if (excludeMediaId != null && item.mediaId == excludeMediaId) continue

            if (item.embedding.size == queryEmbedding.size) {
                validVectorsCount++
                val semSim = cosineSimilarity(queryEmbedding, item.embedding)
                val metaScore = computeMetadataScore(item.modifiedTime, timeFilter)

                val finalScore = if (timeFilter == TimeFilter.ALL) {
                    semSim
                } else {
                    (semanticWeight * semSim) + (metadataWeight * metaScore)
                }

                val level = getConfidenceLevel(finalScore)
                val pct = (finalScore.coerceIn(0f, 1f) * 100).toInt()
                val explanation = buildExplanation(level, pct, queryText, timeFilter, excludeMediaId != null)

                allResults.add(SearchResult(item.mediaId, item.uriString, finalScore, level, pct, explanation))
            }
        }

        val sorted = when (sortOrder) {
            SortOrder.RELEVANCE -> allResults.sortedByDescending { it.similarityScore }
            SortOrder.NEWEST -> allResults.sortedByDescending { it.similarityScore }
            SortOrder.OLDEST -> allResults.sortedBy { it.similarityScore }
        }

        if (sorted.isEmpty()) return emptyList()

        val bestScore = sorted.first().similarityScore
        val adaptiveThreshold = max(minThreshold, bestScore * RELATIVE_SCORE_FACTOR)

        val uniqueUris = mutableSetOf<String>()
        val filteredResults = mutableListOf<SearchResult>()

        for (res in sorted) {
            if (res.similarityScore >= adaptiveThreshold && res.similarityScore >= minThreshold) {
                if (uniqueUris.add(res.uriString)) {
                    filteredResults.add(res)
                    if (filteredResults.size >= maxResults) break
                }
            }
        }

        if (filteredResults.isEmpty() && bestScore >= 0.10f) {
            for (res in sorted.take(3)) {
                if (uniqueUris.add(res.uriString)) {
                    filteredResults.add(res.copy(confidenceLevel = ConfidenceLevel.LOW))
                }
            }
        }

        Log.i(
            TAG,
            "SEARCH DIAGNOSTICS | Searched: $validVectorsCount | Best Score: ${String.format("%.4f", bestScore)} | Results: ${filteredResults.size}"
        )

        return filteredResults
    }

    fun findSimilarMemories(
        selectedMediaId: Long,
        indexMap: Map<Long, IndexedImage>
    ): List<SearchResult> {
        val selectedItem = indexMap[selectedMediaId] ?: return emptyList()
        return searchTopK(
            queryEmbedding = selectedItem.embedding,
            indexMap = indexMap,
            maxResults = MAX_RESULTS,
            minThreshold = MINIMUM_DISPLAY_THRESHOLD,
            excludeMediaId = selectedMediaId
        )
    }

    fun computeDuplicateGroups(
        indexMap: Map<Long, IndexedImage>,
        threshold: Float = NEAR_DUPLICATE_THRESHOLD
    ): List<DuplicateGroup> {
        val items = indexMap.values.toList()
        val processedIds = mutableSetOf<Long>()
        val groups = mutableListOf<DuplicateGroup>()

        for (i in items.indices) {
            val itemA = items[i]
            if (processedIds.contains(itemA.mediaId)) continue

            val similar = mutableListOf<IndexedImage>()
            var simSum = 0f

            for (j in (i + 1) until items.size) {
                val itemB = items[j]
                if (processedIds.contains(itemB.mediaId)) continue

                val sim = cosineSimilarity(itemA.embedding, itemB.embedding)
                if (sim >= threshold) {
                    similar.add(itemB)
                    simSum += sim
                    processedIds.add(itemB.mediaId)
                }
            }

            if (similar.isNotEmpty()) {
                processedIds.add(itemA.mediaId)
                val avgSim = simSum / similar.size
                groups.add(DuplicateGroup(primaryImage = itemA, similarImages = similar, averageSimilarity = avgSim))
            }
        }

        return groups
    }

    fun computeMemoryInsights(indexMap: Map<Long, IndexedImage>): MemoryInsights {
        val total = indexMap.size
        if (total == 0) {
            return MemoryInsights(0, 0, "None", 0)
        }

        val nowSec = System.currentTimeMillis() / 1000L
        val thirtyDaysSec = 30L * 86400L

        var recentCount = 0
        val monthCounts = mutableMapOf<String, Int>()
        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

        for (item in indexMap.values) {
            if (item.modifiedTime > 0) {
                val age = nowSec - item.modifiedTime
                if (age in 0..thirtyDaysSec) {
                    recentCount++
                }

                val monthStr = monthFormat.format(Date(item.modifiedTime * 1000L))
                monthCounts[monthStr] = (monthCounts[monthStr] ?: 0) + 1
            }
        }

        val mostActiveMonth = monthCounts.maxByOrNull { it.value }?.key ?: "N/A"
        val duplicates = computeDuplicateGroups(indexMap)

        return MemoryInsights(
            totalIndexedMemories = total,
            recent30DaysCount = recentCount,
            mostActiveMonth = mostActiveMonth,
            nearDuplicateGroupsCount = duplicates.size
        )
    }

    fun validateEmbedding(vector: FloatArray?): Boolean {
        if (vector == null || vector.size != LocalIndexStore.EXPECTED_DIMENSION) return false
        var normSq = 0f
        for (v in vector) {
            if (v.isNaN() || v.isInfinite()) return false
            normSq += v * v
        }
        val norm = sqrt(normSq)
        return norm in 0.8f..1.2f
    }

    fun runVerificationTests(): Boolean {
        try {
            val dim = 512
            val vecA = FloatArray(dim) { it.toFloat() / 100f }
            val selfSim = cosineSimilarity(vecA, vecA)
            require(selfSim in 0.99f..1.01f) { "Test 1 failed: Self-similarity was $selfSim" }

            val vecB = FloatArray(dim) { (100 - it).toFloat() / 100f }
            val diffSim = cosineSimilarity(vecA, vecB)
            require(diffSim < selfSim) { "Test 2 failed: Diff similarity >= self similarity" }

            val vecC = FloatArray(256) { 1f }
            val mismatchSim = cosineSimilarity(vecA, vecC)
            require(mismatchSim == 0.0f) { "Test 3 failed: Mismatch did not return 0.0" }

            val zeroVec = FloatArray(dim) { 0f }
            val zeroSim = cosineSimilarity(vecA, zeroVec)
            require(zeroSim == 0.0f) { "Test 4 failed: Zero vector did not return 0.0" }

            Log.i(TAG, "All VectorSearch verification tests PASSED successfully.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "VectorSearch verification test failed", e)
            return false
        }
    }
}
