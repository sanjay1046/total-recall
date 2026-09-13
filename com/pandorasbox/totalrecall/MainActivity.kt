package com.pandorasbox.totalrecall

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import com.pandorasbox.totalrecall.ui.theme.IQOOTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var embeddingEngine: EmbeddingEngine
    private lateinit var indexingManager: IndexingManager
    private lateinit var indexStore: LocalIndexStore
    private lateinit var themePreferenceManager: ThemePreferenceManager
    private lateinit var recentSearchManager: RecentSearchManager
    private lateinit var onboardingPreferenceManager: OnboardingPreferenceManager
    private lateinit var favoritesManager: FavoritesManager

    private var voiceSearchManager: VoiceSearchManager? = null

    private var pendingPermissionCallback: (() -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show()
            pendingPermissionCallback?.invoke()
        } else {
            Toast.makeText(this, "Permission is required for this feature.", Toast.LENGTH_LONG).show()
        }
        pendingPermissionCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        embeddingEngine = EmbeddingEngine(this)
        indexingManager = IndexingManager(this, embeddingEngine)
        indexStore = LocalIndexStore(this)
        themePreferenceManager = ThemePreferenceManager(this)
        recentSearchManager = RecentSearchManager(this)
        onboardingPreferenceManager = OnboardingPreferenceManager(this)
        favoritesManager = FavoritesManager(this)

        // Run Milestone 5 verification tests
        VectorSearch.runVerificationTests()

        checkAndRequestGalleryPermission()

        enableEdgeToEdge()
        setContent {
            var currentThemeMode by remember { mutableStateOf(themePreferenceManager.themeMode) }

            IQOOTheme(themeMode = currentThemeMode) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TotalRecallScreen(
                        embeddingEngine = embeddingEngine,
                        indexingManager = indexingManager,
                        indexStore = indexStore,
                        themePreferenceManager = themePreferenceManager,
                        recentSearchManager = recentSearchManager,
                        onboardingPreferenceManager = onboardingPreferenceManager,
                        favoritesManager = favoritesManager,
                        currentThemeMode = currentThemeMode,
                        onThemeChanged = { newMode ->
                            themePreferenceManager.themeMode = newMode
                            currentThemeMode = newMode
                        },
                        onRequestCameraPermission = { onGranted ->
                            requestPermission(Manifest.permission.CAMERA, onGranted)
                        },
                        onRequestAudioPermission = { onGranted ->
                            requestPermission(Manifest.permission.RECORD_AUDIO, onGranted)
                        },
                        modifier = Modifier.fillMaxSize().padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun checkAndRequestGalleryPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun requestPermission(permission: String, onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingPermissionCallback = onGranted
            requestPermissionLauncher.launch(permission)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        embeddingEngine.close()
        voiceSearchManager?.stopListening()
    }
}

@Composable
fun ActionSearchButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors()
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        enabled = enabled,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = icon, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun TotalRecallScreen(
    embeddingEngine: EmbeddingEngine,
    indexingManager: IndexingManager,
    indexStore: LocalIndexStore,
    themePreferenceManager: ThemePreferenceManager,
    recentSearchManager: RecentSearchManager,
    onboardingPreferenceManager: OnboardingPreferenceManager,
    favoritesManager: FavoritesManager,
    currentThemeMode: AppThemeMode,
    onThemeChanged: (AppThemeMode) -> Unit,
    onRequestCameraPermission: (onGranted: () -> Unit) -> Unit,
    onRequestAudioPermission: (onGranted: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Ready") }
    var resultText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var resultsTitle by remember { mutableStateOf("Top Relevant Memories") }
    var searchMetrics by remember { mutableStateOf("") }
    var isIndexing by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var showCameraPreview by remember { mutableStateOf(false) }
    var voiceState by remember { mutableStateOf(VoiceState.IDLE) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showInsightsDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showOnboardingDialog by remember { mutableStateOf(!onboardingPreferenceManager.hasCompletedOnboarding) }
    var selectedSearchResultForDetail by remember { mutableStateOf<SearchResult?>(null) }
    var indexMapState by remember { mutableStateOf<Map<Long, IndexedImage>>(emptyMap()) }
    var recentSearchesList by remember { mutableStateOf(recentSearchManager.getRecentSearches()) }
    var memoryInsightsState by remember { mutableStateOf<MemoryInsights?>(null) }
    var duplicateGroupsState by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }

    // Feature 5: Multi-Select state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedMediaIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // Feature 4: Favorites state
    var favoriteIdsState by remember { mutableStateOf(favoritesManager.getFavoriteIds()) }

    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Unified Search Execution Engine (with Hybrid Search & Smart Query Understanding)
    fun executeSearchWithEmbedding(
        embedding: FloatArray,
        queryLabel: String,
        customTitle: String = "Top Relevant Memories",
        structuredQuery: StructuredSearchQuery? = null
    ) {
        isSearching = true
        hasSearched = true
        resultsTitle = customTitle
        statusText = "Searching memories for '$queryLabel'..."
        coroutineScope.launch {
            val searchStart = System.currentTimeMillis()
            val indexMap = withContext(Dispatchers.IO) { indexStore.loadIndex() }
            indexMapState = indexMap

            if (indexMap.isEmpty()) {
                resultText = "No memories indexed yet. Index your gallery to start searching."
                searchResults = emptyList()
                isSearching = false
                statusText = "Ready"
                return@launch
            }

            val vectorSearchStart = System.currentTimeMillis()
            val results = withContext(Dispatchers.IO) {
                VectorSearch.searchTopK(
                    queryEmbedding = embedding,
                    indexMap = indexMap,
                    maxResults = VectorSearch.MAX_RESULTS,
                    minThreshold = VectorSearch.MINIMUM_DISPLAY_THRESHOLD,
                    structuredQuery = structuredQuery
                )
            }
            val vectorSearchTime = System.currentTimeMillis() - vectorSearchStart
            val totalSearchTime = System.currentTimeMillis() - searchStart

            searchResults = results
            searchMetrics = "Index: ${indexMap.size} images | Hybrid Search: ${vectorSearchTime}ms | Total: ${totalSearchTime}ms"

            val hasLowFallback = results.any { it.confidenceLevel == ConfidenceLevel.LOW }
            if (results.isEmpty()) {
                resultText = "No confident memories found for '$queryLabel'.\nTry describing the object, place, activity, or scene differently."
            } else if (hasLowFallback) {
                resultText = "Showing the best available matches for '$queryLabel'."
            } else {
                resultText = "${results.size} relevant memories found."
            }
            isSearching = false
            statusText = "Ready"
        }
    }

    fun executeFindSimilarMemories(selectedResult: SearchResult) {
        isSearching = true
        hasSearched = true
        resultsTitle = "Similar Memories"
        statusText = "Finding memories similar to selected photo..."
        coroutineScope.launch {
            val searchStart = System.currentTimeMillis()
            val indexMap = withContext(Dispatchers.IO) { indexStore.loadIndex() }
            indexMapState = indexMap

            val results = withContext(Dispatchers.IO) {
                VectorSearch.findSimilarMemories(
                    selectedMediaId = selectedResult.mediaId,
                    indexMap = indexMap
                )
            }
            val totalSearchTime = System.currentTimeMillis() - searchStart

            searchResults = results
            searchMetrics = "Searched ${indexMap.size} memories | Total: ${totalSearchTime}ms"

            if (results.isEmpty()) {
                resultText = "No similar memories found."
            } else {
                resultText = "${results.size} similar memories found."
            }
            isSearching = false
            statusText = "Ready"
        }
    }

    fun startFullIndexingAndAutoSearch() {
        isIndexing = true
        statusText = "Preparing your memories..."
        coroutineScope.launch {
            indexingManager.ensureCompleteIndexing(Int.MAX_VALUE) { current, total, newlyIndexed, skipped, msg ->
                statusText = msg
                resultText = "Preparing your memories...\nProcessed: $current / $total photos\nNew: $newlyIndexed | Skipped (Unchanged): $skipped\n\nPlease wait before searching."
            }
            isIndexing = false

            val queryToAutoRun = indexingManager.pendingQuery
            if (!queryToAutoRun.isNullOrBlank()) {
                indexingManager.pendingQuery = null
                val structured = LocalRuleBasedQueryUnderstanding.understand(queryToAutoRun)
                val textEmbedding = withContext(Dispatchers.IO) {
                    embeddingEngine.generateTextEmbedding(structured.semanticQuery)
                }
                if (textEmbedding != null) {
                    executeSearchWithEmbedding(textEmbedding, structured.originalQuery, "Top Relevant Memories", structured)
                }
            } else {
                statusText = "Gallery index is complete and ready."
                resultText = "Complete gallery indexing finished."
            }
        }
    }

    fun triggerTextSearch(rawQuery: String) {
        if (rawQuery.isBlank()) {
            resultText = "Search your memories naturally."
            return
        }
        if (!embeddingEngine.isInitialized) {
            resultText = "REAL TEXT-IMAGE SEMANTIC RETRIEVAL NOT AVAILABLE YET\n\nMissing ONNX model files in assets."
            return
        }

        // Add to Recent Searches
        recentSearchManager.addRecentSearch(rawQuery)
        recentSearchesList = recentSearchManager.getRecentSearches()

        indexingManager.pendingQuery = rawQuery

        if (indexingManager.currentState == IndexState.COMPLETE) {
            val targetQuery = indexingManager.pendingQuery
            indexingManager.pendingQuery = null
            if (targetQuery != null) {
                coroutineScope.launch {
                    val structured = LocalRuleBasedQueryUnderstanding.understand(targetQuery)
                    val textEmbedding = withContext(Dispatchers.IO) {
                        embeddingEngine.generateTextEmbedding(structured.semanticQuery)
                    }
                    if (textEmbedding != null) {
                        executeSearchWithEmbedding(textEmbedding, structured.originalQuery, "Top Relevant Memories", structured)
                    } else {
                        resultText = "Failed to generate text embedding."
                    }
                }
            }
        } else {
            resultText = "Preparing complete gallery index before search...\nQuery '$rawQuery' will auto-run when complete."
            if (indexingManager.currentState == IndexState.NOT_STARTED) {
                startFullIndexingAndAutoSearch()
            }
        }
    }

    fun handleCapturedCameraImage(bitmap: Bitmap) {
        showCameraPreview = false
        statusText = "Generating camera image embedding..."
        coroutineScope.launch {
            val cameraEmbedding = withContext(Dispatchers.IO) {
                embeddingEngine.generateImageEmbedding(bitmap)
            }
            bitmap.recycle()

            if (cameraEmbedding != null && VectorSearch.validateEmbedding(cameraEmbedding)) {
                if (indexingManager.currentState == IndexState.COMPLETE) {
                    executeSearchWithEmbedding(cameraEmbedding, "Captured Photo", "Photos Similar to Camera Capture")
                } else {
                    resultText = "Preparing complete gallery index before searching camera photo..."
                    if (indexingManager.currentState == IndexState.NOT_STARTED) {
                        startFullIndexingAndAutoSearch()
                    }
                }
            } else {
                resultText = "Failed to generate embedding for camera photo."
            }
        }
    }

    fun shareMultipleSelectedImages() {
        if (selectedMediaIds.isEmpty()) return
        try {
            val selectedUris = searchResults
                .filter { selectedMediaIds.contains(it.mediaId) }
                .map { Uri.parse(it.uriString) }

            if (selectedUris.isEmpty()) return

            val clipData = ClipData.newRawUri("Memory Images", selectedUris.first())
            for (i in 1 until selectedUris.size) {
                clipData.addItem(ClipData.Item(selectedUris[i]))
            }

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(selectedUris))
                this.clipData = clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooserIntent = Intent.createChooser(shareIntent, "Share Selected Memories").apply {
                this.clipData = clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to share selected memories.", Toast.LENGTH_SHORT).show()
        }
    }

    // Voice Search Manager Setup
    val voiceSearchManager = remember {
        VoiceSearchManager(
            context = context,
            onSpeechRecognized = { text ->
                searchQuery = text
                triggerTextSearch(text)
            },
            onError = { errMsg ->
                resultText = "Voice search error: $errMsg"
                statusText = "Ready"
            },
            onStateChanged = { state ->
                voiceState = state
                statusText = when (state) {
                    VoiceState.LISTENING -> "Listening... Speak now."
                    VoiceState.PROCESSING -> "Processing speech..."
                    VoiceState.ERROR -> "Voice search failed."
                    VoiceState.IDLE -> "Ready"
                }
            }
        )
    }

    // First-time Onboarding Dialog
    if (showOnboardingDialog) {
        OnboardingDialog(
            onOnboardingComplete = {
                onboardingPreferenceManager.hasCompletedOnboarding = true
                showOnboardingDialog = false
            }
        )
    }

    // Clear History Dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear recent searches?") },
            text = { Text("This will delete your search history. Your indexed photos and search capability will not be affected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        recentSearchManager.clearRecentSearches()
                        recentSearchesList = emptyList()
                        showClearHistoryDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialogs
    if (selectedSearchResultForDetail != null) {
        val detailResult = selectedSearchResultForDetail!!
        ImageDetailDialog(
            result = detailResult,
            indexedImage = indexMapState[detailResult.mediaId],
            favoritesManager = favoritesManager,
            onFindSimilar = { targetResult ->
                executeFindSimilarMemories(targetResult)
            },
            onFavoriteToggled = {
                favoriteIdsState = favoritesManager.getFavoriteIds()
            },
            onDismiss = {
                selectedSearchResultForDetail = null
            }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            themePreferenceManager = themePreferenceManager,
            indexedCount = indexMapState.size,
            onReindexClicked = {
                startFullIndexingAndAutoSearch()
            },
            onDismiss = {
                showSettingsDialog = false
                onThemeChanged(themePreferenceManager.themeMode)
            }
        )
    }

    if (showInsightsDialog) {
        val insights = memoryInsightsState ?: MemoryInsights(0, 0, "None", 0)
        MemoryInsightsDialog(
            insights = insights,
            duplicateGroups = duplicateGroupsState,
            onDismiss = {
                showInsightsDialog = false
            }
        )
    }

    if (showCameraPreview) {
        CameraSearchPreview(
            onImageCaptured = { bitmap ->
                handleCapturedCameraImage(bitmap)
            },
            onClose = {
                showCameraPreview = false
            }
        )
    } else {
        Column(
            modifier = modifier
                .verticalScroll(scrollState)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Bar with Title, Quick Theme Toggle, and Settings Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TOTAL RECALL",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Search your memories naturally",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Insights Button
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                val map = withContext(Dispatchers.IO) { indexStore.loadIndex() }
                                indexMapState = map
                                val insights = withContext(Dispatchers.IO) { VectorSearch.computeMemoryInsights(map) }
                                val duplicates = withContext(Dispatchers.IO) { VectorSearch.computeDuplicateGroups(map) }
                                memoryInsightsState = insights
                                duplicateGroupsState = duplicates
                                showInsightsDialog = true
                            }
                        }
                    ) {
                        Text("📊", style = MaterialTheme.typography.titleMedium)
                    }

                    // Quick Home Screen Theme Toggle (Sun/Moon)
                    IconButton(
                        onClick = {
                            val newMode = when (currentThemeMode) {
                                AppThemeMode.LIGHT -> AppThemeMode.DARK
                                AppThemeMode.DARK -> AppThemeMode.LIGHT
                                AppThemeMode.SYSTEM -> AppThemeMode.DARK
                            }
                            onThemeChanged(newMode)
                        }
                    ) {
                        Text(
                            text = if (currentThemeMode == AppThemeMode.DARK) "☀️" else "🌙",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    IconButton(onClick = { showSettingsDialog = true }) {
                        Text("⚙️", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status Card for Model Initialization
            if (!embeddingEngine.isInitialized) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Status: ONNX Models Missing from Assets",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "To enable true CLIP semantic search, place clip_vision.onnx, clip_text.onnx, vocab.json, and merges.txt inside app/src/main/assets/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Search Input Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search your memories...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Consistent Reusable Action Buttons: Text Search, Camera Search, Voice Search
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ActionSearchButton(
                    icon = "🔍",
                    label = "Text",
                    onClick = { triggerTextSearch(searchQuery) },
                    modifier = Modifier.weight(1f),
                    enabled = !isSearching && !isIndexing
                )

                Spacer(modifier = Modifier.width(8.dp))

                ActionSearchButton(
                    icon = "📷",
                    label = "Camera",
                    onClick = {
                        onRequestCameraPermission {
                            showCameraPreview = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSearching && !isIndexing
                )

                Spacer(modifier = Modifier.width(8.dp))

                ActionSearchButton(
                    icon = if (voiceState == VoiceState.LISTENING) "⏹" else "🎤",
                    label = if (voiceState == VoiceState.LISTENING) "Stop" else "Voice",
                    onClick = {
                        onRequestAudioPermission {
                            if (voiceState == VoiceState.LISTENING) {
                                voiceSearchManager.stopListening()
                            } else {
                                voiceSearchManager.startListening()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSearching && !isIndexing,
                    colors = if (voiceState == VoiceState.LISTENING)
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else
                        ButtonDefaults.buttonColors()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Privacy & On-Device AI Indicator Badge
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "🔒 Private • Offline • On-device AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Feature 4: Favorites Home Section
            if (favoriteIdsState.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "⭐ Favorite Memories",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        favoriteIdsState.forEach { favId ->
                            val favItem = indexMapState[favId]
                            if (favItem != null) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .padding(end = 8.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedSearchResultForDetail = SearchResult(
                                                mediaId = favItem.mediaId,
                                                uriString = favItem.uriString,
                                                similarityScore = 1.0f,
                                                confidenceLevel = ConfidenceLevel.HIGH,
                                                percentageMatch = 100,
                                                explanation = "Favorite memory"
                                            )
                                        }
                                ) {
                                    Image(
                                        painter = rememberAsyncImagePainter(model = Uri.parse(favItem.uriString)),
                                        contentDescription = "Favorite Memory",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Recent Searches Section with Horizontal Scroll & Clear Action
            if (recentSearchesList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Searches",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { showClearHistoryDialog = true },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Clear",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        recentSearchesList.forEach { recentQuery ->
                            SuggestionChip(
                                onClick = {
                                    searchQuery = recentQuery
                                    triggerTextSearch(recentQuery)
                                },
                                label = {
                                    Text(
                                        text = recentQuery,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (searchMetrics.isNotEmpty()) {
                Text(
                    text = searchMetrics,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (resultText.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = resultText,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Search Results Section with Multi-Select Toggle
            if (searchResults.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = resultsTitle,
                        style = MaterialTheme.typography.titleMedium
                    )

                    // Multi-Select Toggle Button
                    TextButton(
                        onClick = {
                            isMultiSelectMode = !isMultiSelectMode
                            if (!isMultiSelectMode) {
                                selectedMediaIds = emptySet()
                            }
                        }
                    ) {
                        Text(if (isMultiSelectMode) "Cancel Select" else "Select")
                    }
                }

                // Multi-Select Share Header Bar
                if (isMultiSelectMode) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${selectedMediaIds.size} selected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )

                            Button(
                                onClick = { shareMultipleSelectedImages() },
                                enabled = selectedMediaIds.isNotEmpty()
                            ) {
                                Text("📤 Share Selected")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column {
                    searchResults.forEach { result ->
                        val badgeColor = Color(result.confidenceLevel.colorHex)
                        val isSelected = selectedMediaIds.contains(result.mediaId)

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable {
                                    if (isMultiSelectMode) {
                                        selectedMediaIds = if (isSelected) {
                                            selectedMediaIds - result.mediaId
                                        } else {
                                            selectedMediaIds + result.mediaId
                                        }
                                    } else {
                                        selectedSearchResultForDetail = result
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail Image
                                Box(
                                    modifier = Modifier
                                        .size(90.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                ) {
                                    val painter = rememberAsyncImagePainter(model = Uri.parse(result.uriString))
                                    Image(
                                        painter = painter,
                                        contentDescription = "Memory Image",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )

                                    if (isMultiSelectMode && isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0x66000000)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("✓", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                // Information & Confidence Badge & Explanation
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = badgeColor,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = result.confidenceLevel.label,
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${result.percentageMatch}% Similarity",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    if (result.explanation.isNotEmpty()) {
                                        Text(
                                            text = result.explanation,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = if (isMultiSelectMode) {
                                            if (isSelected) "Selected" else "Tap to select"
                                        } else {
                                            "Tap to view & find similar"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            } else if (hasSearched && !isSearching && !isIndexing) {
                Text(
                    text = "No confident memories found for this search.\nTry describing the object, place, activity, or scene differently.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (isIndexing) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Indexing Management
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Index Complete Gallery
            OutlinedButton(
                onClick = {
                    startFullIndexingAndAutoSearch()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isIndexing && !isSearching
            ) {
                Text("Reindex Complete Gallery")
            }
        }
    }
}

private var hasSearchedState = false
