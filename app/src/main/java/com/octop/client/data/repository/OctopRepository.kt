package com.octop.client.data.repository

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.octop.client.data.model.AgentItem
import com.octop.client.data.model.CreateThreadResponse
import com.octop.client.data.model.HistoryResponse
import com.octop.client.data.model.ResolvedModel
import com.octop.client.data.model.ThreadItem
import com.octop.client.data.network.ApiClient
import com.octop.client.data.network.LoginRequest
import com.octop.client.data.network.LoginResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一数据仓库：收敛 REST API 网络调用与后端多态包装结构的反序列化。
 * 屏蔽 JsonElement 弱类型泄露，为 ViewModel 提供强类型 DTO 与 Result 包装。
 */
class OctopRepository(
    private val gson: Gson = Gson()
) {

    suspend fun login(request: LoginRequest): Result<LoginResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = ApiClient.api.login(request)
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                val errStr = resp.errorBody()?.string() ?: ""
                Result.failure(Exception(if (errStr.contains("AUTH_FAILED")) "AUTH_FAILED" else "HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAgents(): Result<List<AgentItem>> = withContext(Dispatchers.IO) {
        try {
            val resp = ApiClient.api.getAgents()
            if (resp.isSuccessful) {
                val list = parseAgentItems(resp.body())
                Result.success(list)
            } else {
                Result.failure(Exception("HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getThreads(agentId: String): Result<List<ThreadItem>> = withContext(Dispatchers.IO) {
        try {
            val resp = ApiClient.api.getThreads(agentId)
            if (resp.isSuccessful) {
                val list = parseThreadItems(resp.body())
                Result.success(list)
            } else {
                Result.failure(Exception("HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createThread(agentId: String): Result<CreateThreadResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = ApiClient.api.createThread(agentId)
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                Result.failure(Exception("HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getThreadHistory(
        agentId: String,
        threadId: String,
        limit: Int = 200,
        offset: Int = 0
    ): Result<HistoryResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = ApiClient.api.getThreadHistory(agentId, threadId, limit, offset)
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                Result.failure(Exception("HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getResolvedModels(): Result<List<ResolvedModel>> = withContext(Dispatchers.IO) {
        try {
            val resp = ApiClient.api.getResolvedModels()
            if (resp.isSuccessful && resp.body() != null) {
                Result.success(resp.body()!!)
            } else {
                Result.failure(Exception("HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateThreadComposer(
        agentId: String,
        threadId: String,
        body: Map<String, Any?>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resp = ApiClient.api.updateThreadComposer(agentId, threadId, body)
            if (resp.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("HTTP ${resp.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 内部私有解析方法，处理后端多态（数组、items、agents、threads 等包装结构）
    private fun parseAgentItems(element: JsonElement?): List<AgentItem> {
        if (element == null) return emptyList()
        val listType = object : TypeToken<List<AgentItem>>() {}.type
        return try {
            if (element.isJsonArray) {
                gson.fromJson<List<AgentItem>>(element, listType) ?: emptyList()
            } else if (element.isJsonObject) {
                val obj = element.asJsonObject
                val array = obj.getAsJsonArray("items")
                    ?: obj.getAsJsonArray("agents")
                    ?: obj.getAsJsonArray("data")
                if (array != null) {
                    gson.fromJson<List<AgentItem>>(array, listType) ?: emptyList()
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseThreadItems(element: JsonElement?): List<ThreadItem> {
        if (element == null) return emptyList()
        val listType = object : TypeToken<List<ThreadItem>>() {}.type
        return try {
            if (element.isJsonArray) {
                gson.fromJson<List<ThreadItem>>(element, listType) ?: emptyList()
            } else if (element.isJsonObject) {
                val obj = element.asJsonObject
                val array = obj.getAsJsonArray("threads")
                    ?: obj.getAsJsonArray("items")
                    ?: obj.getAsJsonArray("data")
                if (array != null) {
                    gson.fromJson<List<ThreadItem>>(array, listType) ?: emptyList()
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
