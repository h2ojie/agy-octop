package com.octop.client.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageCompressor {

    private const val MAX_DIMENSION = 2048
    private const val JPEG_QUALITY = 82
    private const val MIN_COMPRESS_SIZE = 1024 * 1024 // 1MB

    suspend fun compressIfNeeded(
        context: Context,
        uri: Uri,
        mimeType: String?,
        size: Long
    ): File? = withContext(Dispatchers.IO) {
        val mime = (mimeType ?: "").lowercase()
        // 仅处理静态图片，跳过 GIF 或非图片
        if (!mime.startsWith("image/") || mime.contains("gif") || size <= MIN_COMPRESS_SIZE) {
            return@withContext null
        }

        try {
            // 1. 读取原图尺寸
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return@withContext null

            // 2. 计算 inSampleSize（降采样比例）
            var sampleSize = 1
            var w = origWidth
            var h = origHeight
            while (w > MAX_DIMENSION || h > MAX_DIMENSION) {
                sampleSize *= 2
                w /= 2
                h /= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize
            options.inPreferredConfig = Bitmap.Config.RGB_565 // 节约内存

            val sampledBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return@withContext null

            // 3. 读取并纠正 EXIF 旋转角度
            val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
                try {
                    val exif = ExifInterface(stream)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } catch (e: Exception) {
                    0f
                }
            } ?: 0f

            val finalBitmap = if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(
                    sampledBitmap, 0, 0, sampledBitmap.width, sampledBitmap.height, matrix, true
                )
                if (rotated != sampledBitmap) {
                    sampledBitmap.recycle()
                }
                rotated
            } else {
                sampledBitmap
            }

            // 4. 输出到临时缓存文件
            val compressDir = File(context.cacheDir, "compressed").apply {
                if (!exists()) mkdirs()
            }
            val tempFile = File(compressDir, "compressed_${UUID.randomUUID()}.jpg")
            FileOutputStream(tempFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            finalBitmap.recycle()

            Log.d("ImageCompressor", "Compressed image from ${size / 1024}KB to ${tempFile.length() / 1024}KB")
            tempFile
        } catch (e: Exception) {
            Log.e("ImageCompressor", "Failed to compress image", e)
            null
        }
    }
}
