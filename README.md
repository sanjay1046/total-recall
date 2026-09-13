# Total Recall

> Your memories. Searchable.

Total Recall is an AI-powered Android application that turns a user's phone gallery into a searchable personal visual memory.

Instead of manually scrolling through thousands of photos or remembering filenames, users can search their gallery using natural language, voice, or another image.

---

## Problem

Modern phone galleries can contain thousands of photos, making it difficult to find a specific memory.

Traditional gallery search often depends on:

- File names
- Dates
- Folders
- Basic metadata
- Manual scrolling

However, people usually remember a photo by its content rather than its filename.

For example:

> "Find my sunset photos"

> "Show me recent food pictures"

> "Find photos similar to this"

Total Recall is designed around the way people naturally remember their photos.

---

## Solution

Total Recall converts gallery images into semantic embeddings and stores them locally on the device.

When a user searches using text, voice, or an image:

1. The input is converted into an embedding.
2. The embedding is compared with gallery image embeddings.
3. Cosine similarity is used to measure semantic relevance.
4. Metadata can be used to improve ranking and filtering.
5. The most relevant memories are displayed.

CLIP performs the core visual retrieval, while an optional LLM can help understand complex natural-language queries.

### Core idea

> The LLM understands what the user means, and CLIP finds what the user is looking for.

---

## Features

### Natural Language Search

Search photos using descriptions instead of filenames.

Examples:

- `sunset`
- `photos of dogs`
- `food`
- `cars`
- `birthday photos`
- `recent sunset photos`

### Voice Search

Speak a query instead of typing.

The voice input is converted to text and passed through the existing semantic search pipeline.

```text
Voice → Speech-to-Text → CLIP → Gallery Search
```

### Camera Search

Capture an image and find visually similar memories from the gallery.

The camera supports both front and back cameras.

```text
Camera → Image Embedding → Similarity Search → Memories
```

### Find Similar Memories

Open any discovered memory and search for other visually similar images from the gallery.

The existing image embeddings are reused instead of requiring another image model.

### Hybrid Search

Combines semantic similarity with available photo metadata.

For example:

```text
"recent dog photos"
```

can be interpreted as:

```text
Semantic Query: dog
Time Filter: recent
Sort: newest
```

This allows semantic relevance and metadata relevance to work together.

### Smart Query Understanding

Natural-language queries can be converted into structured search information.

An optional LLM can help understand queries such as:

```text
"Show me recent food photos from my trip"
```

The system can identify the semantic concept, supported time filters, and sorting requirements.

A local rule-based fallback keeps the search functional when an LLM or network connection is unavailable.

### Duplicate and Near-Duplicate Detection

Existing image embeddings are reused to identify visually similar or near-duplicate memories.

The feature is designed for discovery and organization and does not automatically delete photos.

### Memory Insights

Provides insights based on actual gallery and indexed data.

Examples include:

- Total indexed memories
- Recent photo activity
- Photo activity over time
- Near-duplicate groups
- Available metadata-based insights

The application does not fabricate gallery statistics.

### Search History

Stores recent searches locally.

Features include:

- Up to 5 recent searches
- Automatic deduplication
- Quick re-search
- Persistent history
- Individual removal
- Clear all history

### Memory Actions

After finding a memory, users can:

- Share the image
- View photo details
- Open the image in the device gallery
- Add it to Favorites
- Find similar memories

Multiple search results can also be selected and shared using Android's native sharing system.

### Favorites

Users can mark important memories as favorites.

Favorite references are stored locally and persist across app restarts without duplicating the original image.

### Smart Search Results

Total Recall does not blindly display results simply because they have the highest similarity score.

Depending on relevance, the application can show messages such as:

```text
8 relevant memories found
```

or:

```text
Showing the best available matches
```

or:

```text
No confident memories found

Try describing the memory differently.
```

Similarity scores are presented as similarity rather than probability.

### Why This Result?

Users can see why a memory appeared in the results.

For example:

```text
Why this memory?

Strong visual match for "sunset"
Recent photo
```

Explanations are based on actual similarity and available metadata.

### Indexing Progress

Gallery indexing provides real progress information.

Example:

```text
Preparing your memories

1,824 / 3,247 photos

AI is learning your gallery...
```

Previously indexed images are not unnecessarily processed again.

### First-Time Onboarding

A lightweight onboarding experience introduces:

- Natural-language search
- On-device AI
- Privacy
- Local memory indexing

The onboarding is shown only on the first use.

---

## Architecture

```text
                         TOTAL RECALL
                              |
              +---------------+---------------+
              |               |               |
             Text           Voice           Camera
              |               |               |
              |          Speech-to-Text        |
              |               |               |
              +---------------+---------------+
                              |
                              v
                   Query / Input Understanding
                              |
                     +--------+--------+
                     |                 |
              LLM (Optional)     Local Fallback
                     |                 |
                     +--------+--------+
                              |
                              v
                    Structured Search Query
                              |
                              v
                       CLIP Encoder
                              |
                              v
                    Local Image Embeddings
                              |
                              v
                     Hybrid Search / Ranking
                              |
                              v
                      Relevant Memories
                              |
              +---------------+---------------+
              |               |               |
          Image Preview   Find Similar     Actions
              |               |               |
          Favorites       Similarity      Share
          Details                         Gallery
```

---

## Gallery Indexing

The gallery indexing pipeline is:

```text
Phone Gallery
      |
      v
MediaStore
      |
      v
Image Preprocessing
      |
      v
CLIP Vision Encoder
      |
      v
L2 Normalized Embedding
      |
      v
Local Index
```

Each indexed image is associated with its media identifier or URI, available metadata, and embedding.

The same local index can be reused by:

- Semantic search
- Camera search
- Find Similar Memories
- Near-duplicate detection
- Memory Insights

---

## Text Search Pipeline

```text
User Query
    |
    v
Query Understanding
    |
    v
Semantic Query
    |
    v
CLIP Text Encoder
    |
    v
Text Embedding
    |
    v
Cosine Similarity
    |
    v
Hybrid Ranking
    |
    v
Relevant Memories
```

---

## Camera Search Pipeline

```text
Camera Capture
      |
      v
Image Preprocessing
      |
      v
CLIP Vision Encoder
      |
      v
Image Embedding
      |
      v
Cosine Similarity
      |
      v
Gallery Memories
```

---

## Privacy

Privacy is a core design principle of Total Recall.

The core image retrieval pipeline runs on-device.

The application does not require:

- Uploading the gallery to a server
- Cloud image storage
- Uploading image embeddings
- Sending gallery images to an LLM

If an external LLM is configured for query understanding, only the user's text query is sent for that operation.

Core image retrieval remains independent of the external LLM.

```text
Gallery Images
     |
     v
On-device Processing
     |
     v
Local Embeddings
     |
     v
Local Search
```

Sharing occurs only when the user explicitly chooses to share a memory through Android's sharing system.

---

## Technology Stack

### Android

- Kotlin
- Android SDK
- Jetpack Compose
- Android MediaStore
- CameraX
- Speech Recognition

### Machine Learning

- CLIP
- ONNX Runtime
- Local image embeddings
- Local text embeddings
- Cosine similarity

### Natural Language

- CLIP tokenizer
- Optional LLM-based query understanding
- Local rule-based fallback

### Storage

- Local embedding index
- Local search history
- Local favorites
- Android MediaStore metadata

---

## AI Architecture

Total Recall separates query understanding from visual retrieval.

### CLIP

CLIP is the core visual retrieval model.

It connects:

```text
Text <----> Images
```

This allows queries such as:

```text
"sunset at the beach"
```

to retrieve visually relevant images without depending on filenames.

### LLM

The LLM is not used to search thousands of images.

Its role is query understanding.

For example:

```text
User:
"Show me the recent food pictures from my trip"
```

The query-understanding layer can identify:

```text
Semantic concept:
food

Time:
recent

Additional context:
trip
```

CLIP then performs the actual visual retrieval.

This separation keeps the retrieval system efficient and allows core search to remain independent of an external LLM.

---

## Performance

The application is designed to work with potentially large photo libraries while keeping processing local.

Important design decisions include:

- Reusing existing image embeddings
- Avoiding unnecessary re-indexing
- Background processing for expensive operations
- URI-based image handling
- Disk-based ONNX model loading
- Avoiding unnecessary large in-memory model copies

Large ONNX models are loaded from disk rather than reading the complete model into memory with operations such as `readBytes()`.

---

## User Flow

```text
Open Total Recall
       |
       v
Gallery Indexing
       |
       v
Home Screen
       |
       +-------------------+
       |         |         |
      Text     Voice     Camera
       |         |         |
       +---------+---------+
                 |
                 v
           Search Memories
                 |
                 v
          Relevant Results
                 |
       +---------+---------+
       |         |         |
    Preview   Similar    Actions
                |          |
                |     +----+----+
                |     |    |    |
                |   Share Details
                |       |
                |    Gallery
                |
            Find Similar
```

---

## Example Use Cases

### Find a Memory by Description

```text
User:
"Find photos of sunsets"

Total Recall:
Returns visually relevant sunset memories.
```

### Find Recent Memories

```text
User:
"Show me recent food photos"

Total Recall:
Understands the semantic query and recency requirement,
then ranks relevant memories accordingly.
```

### Find Similar Photos

```text
User:
Opens a photo
        |
        v
Find Similar Memories
        |
        v
Similar photos from the gallery
```

### Share a Discovered Memory

```text
Search
  |
  v
Open Memory
  |
  v
Share
  |
  v
Android Native Share Sheet
```

---

## Why Total Recall?

Traditional gallery search asks:

> "What is the file called?"

Total Recall asks:

> "What do you remember about the photo?"

The goal is to make a personal photo gallery searchable in the same way people naturally remember their experiences.

---

## Future Possibilities

Potential future improvements include:

- Memory timeline
- Smarter visual collections
- Advanced memory clustering
- Location-aware memory exploration
- Improved temporal search
- More advanced personalized ranking
- More capable on-device query understanding

These features can be added without changing the core CLIP-based retrieval architecture.

---

## Project Status

- [x] Natural-language semantic search
- [x] Voice search
- [x] Camera search
- [x] Front/back camera
- [x] CLIP image embeddings
- [x] CLIP text embeddings
- [x] Local similarity search
- [x] Hybrid search
- [x] Smart query understanding
- [x] Optional LLM integration
- [x] Local fallback query understanding
- [x] Near-duplicate detection
- [x] Memory Insights
- [x] Search History
- [x] Clear Search History
- [x] Find Similar Memories
- [x] Search result explanations
- [x] Smart result confidence
- [x] Indexing progress
- [x] First-time onboarding
- [x] Image sharing
- [x] Photo details
- [x] Open in Gallery
- [x] Favorites
- [x] Multi-select and sharing
- [x] Light/Dark theme
- [x] Local-first architecture

---

## Hackathon

Built for the **iQOO Hackathon 2026 — Chennai City Battle**.

## Team

**Total Recall**

> Remember naturally. Find instantly.
