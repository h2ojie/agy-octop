package com.octop.client.data.network

import com.google.gson.annotations.SerializedName
import com.octop.client.data.model.AgentItem
import com.octop.client.data.model.CreateThreadResponse
import com.octop.client.data.model.HistoryResponse
import com.octop.client.data.model.HitlResumeBody
import com.octop.client.data.model.ThreadItem
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

data class LoginRequest(
    val username: String,
    val password: String,
    @SerializedName("captcha_token") val captchaToken: String? = null
) {
    // 兼容别名
    val captcha_token: String? get() = captchaToken
}

data class LoginResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Long
)

data class UploadAttachmentResponse(
    val path: String,
    @SerializedName("workspace_path") val workspacePath: String,
    val url: String,
    @SerializedName("access_url") val accessUrl: String,
    val filename: String,
    @SerializedName("media_type") val mediaType: String
) {
    // 兼容别名
    val workspace_path: String get() = workspacePath
    val access_url: String get() = accessUrl
    val media_type: String get() = mediaType
}

interface OctopApi {
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @GET("api/agents")
    suspend fun getAgents(): Response<com.google.gson.JsonElement>

    @GET("api/agents/{agent_id}/threads")
    suspend fun getThreads(@Path("agent_id") agentId: String): Response<com.google.gson.JsonElement>

    @POST("api/agents/{agent_id}/threads")
    suspend fun createThread(
        @Path("agent_id") agentId: String
    ): Response<com.octop.client.data.model.CreateThreadResponse>

    @GET("api/agents/{agent_id}/threads/{thread_id}/history")
    suspend fun getThreadHistory(
        @Path("agent_id") agentId: String,
        @Path("thread_id") threadId: String,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0
    ): Response<HistoryResponse>

    @Streaming
    @POST("api/agents/{agent_id}/chat/hitl/resume")
    suspend fun resumeHitl(
        @Path("agent_id") agentId: String,
        @Body body: HitlResumeBody
    ): Response<ResponseBody>

    @Multipart
    @POST("api/agents/{agent_id}/upload")
    suspend fun uploadAttachment(
        @Path("agent_id") agentId: String,
        @Part file: MultipartBody.Part
    ): Response<UploadAttachmentResponse>

    @GET("api/providers/resolved")
    suspend fun getResolvedModels(): Response<List<com.octop.client.data.model.ResolvedModel>>

    @PATCH("api/agents/{agent_id}/threads/{thread_id}")
    suspend fun updateThreadComposer(
        @Path("agent_id") agentId: String,
        @Path("thread_id") threadId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<com.google.gson.JsonElement>
}

object ApiClient {
    private val logging = HttpLoggingInterceptor().apply {
        // 切勿使用 Level.BODY，否则会缓冲阻塞 @Streaming 的 SSE 流并把二进制图片全量打印到 logcat 导致 OOM
        level = HttpLoggingInterceptor.Level.BASIC
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val requestBuilder = chain.request().newBuilder()
            // 自动注入安全头 X-Octop-App-Key
            requestBuilder.header("X-Octop-App-Key", NetworkConfig.appKey)
            // 如果已登录，自动注入 JWT Token
            NetworkConfig.authToken?.let {
                if (it.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $it")
                }
            }
            chain.proceed(requestBuilder.build())
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            // 拦截 401 未授权响应（排除常规登录失败，防止循环弹窗）
            if (response.code == 401 && !request.url.encodedPath.contains("auth/login")) {
                NetworkConfig.notifyAuthExpired()
            }
            response
        }
        .addInterceptor(logging)
        .build()

    @Volatile
    private var retrofit: Retrofit? = null
    @Volatile
    private var currentBaseUrl: String? = null
    @Volatile
    private var apiService: OctopApi? = null

    val api: OctopApi
        get() {
            val url = NetworkConfig.baseUrl
            if (apiService == null || currentBaseUrl != url) {
                synchronized(this) {
                    if (apiService == null || currentBaseUrl != url) {
                        currentBaseUrl = url
                        val newRetrofit = Retrofit.Builder()
                            .baseUrl(url)
                            .client(okHttpClient)
                            .addConverterFactory(GsonConverterFactory.create())
                            .build()
                        retrofit = newRetrofit
                        apiService = newRetrofit.create(OctopApi::class.java)
                    }
                }
            }
            return apiService!!
        }
}
