package com.pandorasbox.totalrecall

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

data class GalleryImage(
    val id: Long,
    val uri: Uri,
    val modifiedTime: Long
)

object GalleryScanner {
    private const val TAG = "GalleryScanner"

    fun scanGallery(context: Context, limit: Int = Int.MAX_VALUE): List<GalleryImage> {
        val imageList = mutableListOf<GalleryImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext() && imageList.size < limit) {
                    val id = cursor.getLong(idColumn)
                    val modified = cursor.getLong(modifiedColumn)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    imageList.add(GalleryImage(id, uri, modified))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning gallery", e)
        }
        return imageList
    }

    fun decodeSampledBitmap(context: Context, uri: Uri, reqWidth: Int = 224, reqHeight: Int = 224): Bitmap? {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false

                context.contentResolver.openInputStream(uri)?.use { secondStream ->
                    return BitmapFactory.decodeStream(secondStream, null, options)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap from uri $uri", e)
        }
        return null
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
