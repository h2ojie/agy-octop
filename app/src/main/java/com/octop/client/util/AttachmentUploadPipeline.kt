package com.octop.client.util

import android.content.Context
import android.net.Uri
import com.octop.client.data.model.AttachmentItem
import com.octop.client.data.network.ApiClient
import com.octop.client.data.network.UploadAttachmentResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID

/**
 * 独立的附件上传管道：
 * 负责智能压缩图片、文件流式缓存拷贝、多部件组装、网络上传以及临时缓存文件自动回收。
 */
object AttachmentUploadPipeline {

    suspend fun upload(
        context: Context,
        agentId: String,
        item: AttachmentItem
    ): Result<UploadAttachmentResponse> = withContext(Dispatchers.IO) {
        var tempToClean: File? = null
        try {
            val uri = Uri.parse(item.localUriString)
            val targetFile: File
            val targetMime: String
            val targetFilename: String

            if (item.isImage) {
                val comp = ImageCompressor.compressIfNeeded(context, uri, item.mimeType, item.size)
                if (comp != null) {
                    tempToClean = comp
                    targetFile = comp
                    targetMime = "image/jpeg"
                    targetFilename = if (item.filename.endsWith(".jpg", true) || item.filename.endsWith(".jpeg", true)) {
                        item.filename
                    } else {
                        "${item.filename.substringBeforeLast('.')}.jpg"
                    }
                } else {
                    val temp = File(context.cacheDir, "upload_${UUID.randomUUID()}_${item.filename}")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    tempToClean = temp
                    targetFile = temp
                    targetMime = item.mimeType
                    targetFilename = item.filename
                }
            } else {
                val temp = File(context.cacheDir, "upload_${UUID.randomUUID()}_${item.filename}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                tempToClean = temp
                targetFile = temp
                targetMime = item.mimeType
                targetFilename = item.filename
            }

            val mediaType = targetMime.toMediaTypeOrNull()
            val requestBody = targetFile.asRequestBody(mediaType)
            val part = MultipartBody.Part.createFormData("file", targetFilename, requestBody)

            val resp = ApiClient.api.uploadAttachment(agentId, part)
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                Result.failure(Exception("HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempToClean?.delete()
        }
    }
}
