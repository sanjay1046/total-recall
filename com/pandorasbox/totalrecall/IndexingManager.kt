package com.pandorasbox.totalrecall

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class IndexState {
    NOT_STARTED,
    INDEXING,
    COMPLETE,
    FAILED
}

data class IndexingStats(
    val requestedCount: Int,
    val successfulCount: Int,
    val failedCount: Int,
    val newIndexedCount: Int,
    val skippedCount: Int,
    val deletedCount: Int,
    val embeddingDimension: Int,
    val totalTimeMs: Long,
    val avgTimeMs: Long,
    val fastTimeMs: Long,
    val slowTimeMs: Long
)

class IndexingManager(private val context: Context, private val embeddingEngine: EmbeddingEngine) {
    companion object {
        private const val TAG = "IndexingManager"
        private const val SAVE_PERIOD = 25
    }

    private val indexMutex = Mutex()
    private val indexStore = LocalIndexStore(context)

    @Volatile
    var currentState: IndexState = IndexState.NOT_STARTED
        private set

    @Volatile
    var pendingQuery: String? = null

    @Volatile
    var totalImages: Int = 0
        private set

    @Volatile
    var processedImages: Int = 0
        private set

    @Volatile
    var newlyIndexedImages: Int = 0
        private set

    @Volatile
    var skippedImages: Int = 0
        private set

    suspend fun ensureCompleteIndexing(
        limit: Int = Int.MAX_VALUE,
        onProgress: (current: Int, total: Int, newlyIndexed: Int, skipped: Int, status: String) -> Unit
    ): IndexingStats = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            currentState = IndexState.INDEXING
            val startTimeTotal = System.currentTimeMillis()
            val existingIndex = indexStore.loadIndex()

            Log.i(TAG, "INDEXING STARTED | State: INDEXING")

            onProgress(0, 0, 0, 0, "Scanning gallery images...")
            val galleryImages = GalleryScanner.scanGallery(context, limit)
            val actualCount = galleryImages.size
            totalImages = actualCount

            if (actualCount == 0) {
                currentState = IndexState.COMPLETE
                onProgress(0, 0, 0, 0, "No images found in gallery.")
                return@withContext IndexingStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            }

            val galleryIds = galleryImages.map { it.id }.toSet()
            
            // Remove stale/deleted entries
            val staleIds = existingIndex.keys.filter { !galleryIds.contains(it) }
            for (staleId in staleIds) {
                existingIndex.remove(staleId)
            }
            if (staleIds.isNotEmpty()) {
                Log.i(TAG, "Purged ${staleIds.size} deleted entries from persistent index.")
            }

            var successfulCount = 0
            var failedCount = 0
            var newIndexedCount = 0
            var skippedCount = 0
            var embeddingDim = LocalIndexStore.EXPECTED_DIMENSION

            val inferenceTimes = mutableListOf<Long>()

            for ((index, item) in galleryImages.withIndex()) {
                val currentNum = index + 1
                processedImages = currentNum

                val existingEntry = existingIndex[item.id]
                if (existingEntry != null && existingEntry.modifiedTime == item.modifiedTime && existingEntry.embedding.size == embeddingDim) {
                    skippedCount++
                    skippedImages = skippedCount
                    successfulCount++
                    onProgress(
                        currentNum,
                        actualCount,
                        newIndexedCount,
                        skippedCount,
                        "Preparing memories... ($currentNum / $actualCount)"
                    )
                    continue
                }

                onProgress(
                    currentNum,
                    actualCount,
                    newIndexedCount,
                    skippedCount,
                    "Indexing memory $currentNum / $actualCount"
                )

                val bitmap = GalleryScanner.decodeSampledBitmap(context, item.uri)
                if (bitmap == null) {
                    failedCount++
                    Log.w(TAG, "Failed to decode bitmap for URI: ${item.uri}")
                    continue
                }

                val inferStart = System.currentTimeMillis()
                val embedding = embeddingEngine.generateImageEmbedding(bitmap)
                val inferTime = System.currentTimeMillis() - inferStart

                bitmap.recycle()

                if (embedding != null && VectorSearch.validateEmbedding(embedding)) {
                    inferenceTimes.add(inferTime)
                    embeddingDim = embedding.size
                    successfulCount++
                    newIndexedCount++
                    newlyIndexedImages = newIndexedCount
                    existingIndex[item.id] = IndexedImage(
                        mediaId = item.id,
                        uriString = item.uri.toString(),
                        modifiedTime = item.modifiedTime,
                        embedding = embedding
                    )

                    if (newIndexedCount % SAVE_PERIOD == 0) {
                        indexStore.saveIndex(existingIndex)
                    }
                } else {
                    failedCount++
                    Log.w(TAG, "Failed to generate valid embedding for URI: ${item.uri}")
                }
            }

            // Final index save
            indexStore.saveIndex(existingIndex)
            currentState = IndexState.COMPLETE

            val totalTime = System.currentTimeMillis() - startTimeTotal
            val avgTime = if (inferenceTimes.isNotEmpty()) inferenceTimes.average().toLong() else 0L
            val fastTime = inferenceTimes.minOrNull() ?: 0L
            val slowTime = inferenceTimes.maxOrNull() ?: 0L

            Log.i(
                TAG,
                "INDEX COMPLETE | Total: $actualCount | New: $newIndexedCount | Skipped: $skippedCount | Total Time: ${totalTime}ms"
            )

            IndexingStats(
                requestedCount = actualCount,
                successfulCount = successfulCount,
                failedCount = failedCount,
                newIndexedCount = newIndexedCount,
                skippedCount = skippedCount,
                deletedCount = staleIds.size,
                embeddingDimension = embeddingDim,
                totalTimeMs = totalTime,
                avgTimeMs = avgTime,
                fastTimeMs = fastTime,
                slowTimeMs = slowTime
            )
        }
    }
}
