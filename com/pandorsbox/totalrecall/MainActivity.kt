package com.pandorsbox.totalrecall

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import androidx.compose.ui.Alignment
import android.util.Log
import android.graphics.Bitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GalleryScreen()
            }
        }
    }
}
fun loadBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        }
    } catch (e: Exception) {
        Log.e("EmbedTest", "Failed to load bitmap", e)
        null
    }
}
fun runSimilarityTest(context: android.content.Context, photos: List<Uri>, engine: EmbeddingEngine) {
    if (photos.size < 3) {
        Log.e("SimTest", "Need at least 3 photos loaded, only have ${photos.size}")
        return
    }

    // Pick 3 photos manually for now — swap these indices to point at
    // two similar photos and one clearly different one from your gallery.
    val uriA = photos[0]
    val uriB = photos[1]
    val uriC = photos[2]

    val bitmapA = loadBitmapFromUri(context, uriA)
    val bitmapB = loadBitmapFromUri(context, uriB)
    val bitmapC = loadBitmapFromUri(context, uriC)

    if (bitmapA == null || bitmapB == null || bitmapC == null) {
        Log.e("SimTest", "One or more bitmaps failed to load")
        return
    }

    val embA = engine.embedImage(bitmapA)
    val embB = engine.embedImage(bitmapB)
    val embC = engine.embedImage(bitmapC)

    val simAB = engine.cosineSimilarity(embA, embB)
    val simAC = engine.cosineSimilarity(embA, embC)
    val simBC = engine.cosineSimilarity(embB, embC)

    Log.d("SimTest", "A-B similarity: $simAB")
    Log.d("SimTest", "A-C similarity: $simAC")
    Log.d("SimTest", "B-C similarity: $simBC")
}
suspend fun runBatchEmbeddingTestAsync(
    context: android.content.Context,
    photos: List<Uri>,
    engine: EmbeddingEngine,
    count: Int,
    onProgress: (Int, Int) -> Unit
) {
    val n = minOf(count, photos.size)
    if (n == 0) {
        Log.e("BatchTest", "No photos available")
        return
    }

    Log.d("BatchTest", "Starting batch of $n images (background thread)")
    val perImageTimes = mutableListOf<Long>()
    val totalStart = System.currentTimeMillis()
    var failures = 0

    for (i in 0 until n) {
        val imgStart = System.currentTimeMillis()
        try {
            val bitmap = loadBitmapFromUri(context, photos[i])
            if (bitmap == null) {
                Log.e("BatchTest", "Image $i: bitmap null, skipping")
                failures++
                continue
            }
            val embedding = engine.embedImage(bitmap)
            bitmap.recycle()
            val elapsed = System.currentTimeMillis() - imgStart
            perImageTimes.add(elapsed)
            Log.d("BatchTest", "Image $i: ${elapsed}ms, embedding length ${embedding.size}")

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onProgress(i + 1, n)
            }

            // Give the JVM/native layer breathing room every 10 images:
            // a brief yield lets GC catch up instead of memory piling up
            // continuously across a tight synchronous loop.
            if ((i + 1) % 10 == 0) {
                System.gc()
                kotlinx.coroutines.delay(50)
            }
        } catch (e: Exception) {
            failures++
            Log.e("BatchTest", "Image $i: CRASH ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    val totalElapsed = System.currentTimeMillis() - totalStart
    val avg = if (perImageTimes.isNotEmpty()) perImageTimes.average() else 0.0
    val runtime = Runtime.getRuntime()
    val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

    Log.d("BatchTest", "=== SUMMARY ===")
    Log.d("BatchTest", "Total images attempted: $n, failures: $failures")
    Log.d("BatchTest", "Total time: ${totalElapsed}ms")
    Log.d("BatchTest", "Avg time/image: ${"%.1f".format(avg)}ms")
    Log.d("BatchTest", "JVM heap used after batch: ${usedMemMb}MB")
}
suspend fun buildPhotoIndex(
    context: android.content.Context,
    photos: List<Uri>,
    engine: EmbeddingEngine,
    count: Int,
    onProgress: (Int, Int) -> Unit
): List<IndexedPhoto> {
    val n = minOf(count, photos.size)
    val results = mutableListOf<IndexedPhoto>()

    Log.d("IndexBuild", "Indexing $n images")

    for (i in 0 until n) {
        try {
            val bitmap = loadBitmapFromUri(context, photos[i])
            if (bitmap == null) {
                Log.e("IndexBuild", "Image $i: bitmap null, skipping")
                continue
            }
            val embedding = engine.embedImage(bitmap)
            bitmap.recycle()
            results.add(IndexedPhoto(photos[i], embedding))

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onProgress(i + 1, n)
            }

            if ((i + 1) % 10 == 0) {
                System.gc()
                kotlinx.coroutines.delay(50)
            }
        } catch (e: Exception) {
            Log.e("IndexBuild", "Image $i: CRASH ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    PhotoIndexStore.save(context, results)
    Log.d("IndexBuild", "Saved ${results.size} indexed photos to disk")
    return results
}
suspend fun searchSimilarPhotos(
    context: android.content.Context,
    queryUri: Uri,
    index: List<IndexedPhoto>,
    engine: EmbeddingEngine,
    topN: Int = 10
): List<Pair<IndexedPhoto, Float>> {
    val bitmap = loadBitmapFromUri(context, queryUri) ?: run {
        Log.e("Search", "Query bitmap null")
        return emptyList()
    }
    val queryEmbedding = engine.embedImage(bitmap)
    bitmap.recycle()

    return index
        .map { item -> item to engine.cosineSimilarity(queryEmbedding, item.embedding) }
        .sortedByDescending { it.second }
        .take(topN)
}
@Composable
fun GalleryScreen() {
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var photoIndex by remember { mutableStateOf<List<IndexedPhoto>>(emptyList()) }
    var indexProgress by remember { mutableStateOf<String?>(null) }
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<Pair<IndexedPhoto, Float>>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, readImagesPermission())
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            launcher.launch(readImagesPermission())
        } else {
            photos = loadGalleryPhotos(context)
            photoIndex = PhotoIndexStore.load(context)
            if (photoIndex.isNotEmpty()) {
                Log.d("IndexBuild", "Loaded ${photoIndex.size} photos from saved index")
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    Scaffold(topBar = { TopAppBar(title = { Text("Total Recall — Gallery Check") }) }) { padding ->
        if (!hasPermission) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Waiting for photo access permission...")
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Button(onClick = {
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        try {
                            val engine = EmbeddingEngine(context)
                            val built = buildPhotoIndex(context, photos, engine, 200) { done, total ->
                                indexProgress = "Indexing $done / $total"
                            }
                            photoIndex = built
                            indexProgress = "Index built: ${built.size} photos"
                        } catch (e: Exception) {
                            Log.e("IndexBuild", "CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
                            indexProgress = "Crashed: ${e.message}"
                        }
                    }
                }) {
                    Text("Build Index (200)")
                }

                indexProgress?.let {
                    Text(it, modifier = Modifier.padding(8.dp))
                }

                Button(onClick = {
                    try {
                        val engine = EmbeddingEngine(context)
                        runSimilarityTest(context, photos, engine)
                    } catch (e: Exception) {
                        Log.e("SimTest", "CRASH: ${e.javaClass.simpleName}: ${e.message}", e)
                    }
                }) {
                    Text("Test Similarity")
                }

                // Main gallery grid — weight(1f) instead of fillMaxSize()
                // so the results grid below it actually gets space too.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(photos) { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(2.dp)
                                .aspectRatio(1f)
                                .clickable {
                                    if (photoIndex.isEmpty()) {
                                        Log.e("Search", "Index is empty — build it first")
                                        return@clickable
                                    }
                                    isSearching = true
                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                                        val engine = EmbeddingEngine(context)
                                        val results = searchSimilarPhotos(context, uri, photoIndex, engine)
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            searchResults = results
                                            isSearching = false
                                        }
                                    }
                                }
                        )
                    }
                }

                // Results section — sibling of the grid above, not nested inside it.
                if (isSearching) {
                    Text("Searching...", modifier = Modifier.padding(8.dp))
                }
                if (searchResults.isNotEmpty()) {
                    Text("Top matches:", modifier = Modifier.padding(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        items(searchResults) { (item, score) ->
                            Column(modifier = Modifier.padding(2.dp)) {
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = null,
                                    modifier = Modifier.aspectRatio(1f)
                                )
                                Text("%.3f".format(score), modifier = Modifier.padding(2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun readImagesPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

fun loadGalleryPhotos(context: android.content.Context): List<Uri> {
    val uris = mutableListOf<Uri>()
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection, null, null, sortOrder
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            uris.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()))
        }
    }
    return uris
}