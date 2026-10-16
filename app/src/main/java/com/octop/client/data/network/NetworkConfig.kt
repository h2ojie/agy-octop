package com.octop.client.data.network

import android.content.Context
import com.octop.client.OctopApp
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object NetworkConfig {
    private const val PREFS_NAME = "octop_config"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_APP_KEY = "app_key"
    private const val KEY_AUTH_TOKEN = "auth_token"

    // 默认配置为你搭建好的中转节点与 DuckDNS 域名
    const val DEFAULT_BASE_URL = "https://h2ojie2026.duckdns.org:8443/"
    const val DEFAULT_APP_KEY = "YOUR_CUSTOM_SECRET_UUID"

    private val prefs by lazy {
        OctopApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var appKey: String
        get() = prefs.getString(KEY_APP_KEY, DEFAULT_APP_KEY) ?: DEFAULT_APP_KEY
        set(value) = prefs.edit().putString(KEY_APP_KEY, value).apply()

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    // 认证会话过期 (401 Unauthorized) 监听回调
    var onAuthExpired: (() -> Unit)? = null

    fun notifyAuthExpired() {
        authToken = null
        onAuthExpired?.invoke()
    }

    fun getWsUrl(agentId: String): String {
        val httpUrl = baseUrl.toHttpUrlOrNull() ?: return ""
        val scheme = if (httpUrl.isHttps) "wss" else "ws"
        val cleanAgentId = agentId.trim().ifEmpty { "default" }
        val builder = httpUrl.newBuilder()
            .scheme(scheme)
            .addPathSegments("api/agents")
            .addPathSegment(cleanAgentId)
            .addPathSegments("chat/ws")
        if (!authToken.isNullOrBlank()) {
            builder.addQueryParameter("token", authToken)
        }
        return builder.build().toString()
    }

    fun getMediaPreviewUrl(agentId: String, workspacePath: String, mimeType: String? = null): String {
        val cleanPath = workspacePath.trimStart('/')
        val cleanAgentId = agentId.trim().ifEmpty { "default" }
        val builder = baseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments("api/agents")
            ?.addPathSegment(cleanAgentId)
            ?.addPathSegments("media/preview")
            ?.addQueryParameter("source", cleanPath)
        if (!mimeType.isNullOrBlank()) {
            builder?.addQueryParameter("mime_type", mimeType)
        }
        return builder?.build()?.toString() ?: ""
    }

    fun getFileDownloadUrl(agentId: String, workspacePath: String): String {
        val cleanPath = workspacePath.trimStart('/')
        val cleanAgentId = agentId.trim().ifEmpty { "default" }
        return baseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments("api/agents")
            ?.addPathSegment(cleanAgentId)
            ?.addPathSegments("workspace/download")
            ?.addQueryParameter("path", cleanPath)
            ?.build()
            ?.toString() ?: ""
    }
}
