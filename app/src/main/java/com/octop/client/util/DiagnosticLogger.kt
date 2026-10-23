package com.octop.client.util

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 全局系统诊断与日志采集管理器
 * 
 * 核心特性：
 * 1. 异步非阻塞：通过 Channel 与单线程后台 IO 队列落盘，绝不占用 UI 渲染与主通信通道；
 * 2. 状态开关受控：可通过 SharedPreferences 切换开关，默认开启 (true)，关闭时 0 I/O 开销；
 * 3. 敏感数据脱敏：自动清洗 Token、密码、AppKey、私密参数；
 * 4. 滚动与自动清理：单文件 > 4MB 滚动，仅保留最近 5 天日志，严控存储占用；
 * 5. 全局未捕获异常捕获：闪退瞬间精准抓取堆栈、机型与环境信息。
 */
object DiagnosticLogger {

    private const val PREFS_NAME = "octop_diagnostic_prefs"
    private const val KEY_LOGGING_ENABLED = "pref_diagnostic_logging_enabled"
    private const val MAX_FILE_SIZE = 4 * 1024 * 1024L // 4MB
    private const val MAX_RETENTION_DAYS = 5

    // 模块标签规范
    const val TAG_WS = "WS"
    const val TAG_NET = "NET"
    const val TAG_STATE = "STATE"
    const val TAG_UI = "UI"
    const val TAG_CRASH = "CRASH"
    const val TAG_GENERAL = "OCTOP"

    @Volatile
    private var isEnabledInMemory: Boolean = true

    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logChannel = Channel<String>(capacity = 1000)

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    // 敏感字段脱敏正则
    private val sensitivePatterns = listOf(
        Regex("""(?i)(token|auth_token|app_key|password|secret|bearer)\s*[:=]\s*["']?([^"',\s&]+)["']?"""),
        Regex("""(?i)"(token|password|auth_token|app_key)"\s*:\s*"[^"]+"""")
    )

    private fun getAppVersionInfo(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
            "${pInfo.versionName} ($code)"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnabledInMemory = prefs.getBoolean(KEY_LOGGING_ENABLED, true)

        startLogConsumer(appCtx)
        installCrashHandler(appCtx)
        pruneOldLogs(appCtx)

        i(TAG_STATE, "DiagnosticLogger initialized. Enabled=$isEnabledInMemory, Version=${getAppVersionInfo(appCtx)}, Device=${Build.MANUFACTURER} ${Build.MODEL}, Android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    }

    fun isLoggingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isEnabledInMemory = prefs.getBoolean(KEY_LOGGING_ENABLED, true)
        return isEnabledInMemory
    }

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        isEnabledInMemory = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOGGING_ENABLED, enabled)
            .apply()
        i(TAG_STATE, "Diagnostic logging switch changed: enabled=$enabled")
    }

    fun d(tag: String, message: String) {
        log("DEBUG", tag, message)
        Log.d("Octop-$tag", message)
    }

    fun i(tag: String, message: String) {
        log("INFO", tag, message)
        Log.i("Octop-$tag", message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message
        log("WARN", tag, fullMsg)
        Log.w("Octop-$tag", fullMsg, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message
        log("ERROR", tag, fullMsg)
        Log.e("Octop-$tag", fullMsg, throwable)
    }

    private fun log(level: String, tag: String, message: String) {
        if (!isEnabledInMemory && level != "ERROR") return
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val sanitizedMsg = sanitize(message)
        val line = "[$timestamp] [$level] [$tag] $sanitizedMsg\n"
        logChannel.trySend(line)
    }

    private fun sanitize(input: String): String {
        var result = input
        for (pattern in sensitivePatterns) {
            result = pattern.replace(result) { mr ->
                val text = mr.value
                val colonIdx = text.indexOf(':')
                val equalIdx = text.indexOf('=')
                val splitIdx = if (colonIdx in 1..equalIdx || equalIdx == -1) colonIdx else equalIdx
                if (splitIdx > 0) {
                    val prefix = text.substring(0, splitIdx + 1)
                    "$prefix ***"
                } else {
                    "***"
                }
            }
        }
        return result
    }

    private fun getLogDir(context: Context): File {
        val dir = File(context.filesDir, "diagnostic_logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getZipDir(context: Context): File {
        val dir = File(context.cacheDir, "diagnostic_zips")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun startLogConsumer(context: Context) {
        scope.launch {
            val logDir = getLogDir(context)
            for (line in logChannel) {
                try {
                    val today = synchronized(dateFormat) { dateFormat.format(Date()) }
                    var file = File(logDir, "octop_$today.log")
                    if (file.exists() && file.length() > MAX_FILE_SIZE) {
                        file = File(logDir, "octop_${today}_${System.currentTimeMillis() % 10000}.log")
                    }
                    FileOutputStream(file, true).bufferedWriter().use { writer ->
                        writer.write(line)
                    }
                } catch (e: Exception) {
                    Log.e("DiagnosticLogger", "Failed to write log line: ${e.message}")
                }
            }
        }
    }

    private fun installCrashHandler(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
                val crashReport = buildString {
                    append("==================== CRASH REPORT ====================\n")
                    append("Time: $timestamp\n")
                    append("App Version: ${getAppVersionInfo(context)}\n")
                    append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND})\n")
                    append("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                    append("Thread: ${thread.name} (id: ${thread.id})\n")
                    append("Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
                    append("Stacktrace:\n")
                    append(Log.getStackTraceString(throwable))
                    append("\n======================================================\n")
                }

                // 崩溃时采用同步写入，确保堆栈完全落盘
                val logDir = getLogDir(context)
                val today = synchronized(dateFormat) { dateFormat.format(Date()) }
                val crashFile = File(logDir, "crash_${today}_${System.currentTimeMillis()}.log")
                crashFile.writeText(crashReport)

                val mainLogFile = File(logDir, "octop_$today.log")
                FileOutputStream(mainLogFile, true).bufferedWriter().use {
                    it.write(crashReport)
                }
            } catch (e: Exception) {
                Log.e("DiagnosticLogger", "Error handling crash log: ${e.message}")
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun pruneOldLogs(context: Context) {
        scope.launch {
            try {
                val logDir = getLogDir(context)
                val files = logDir.listFiles() ?: return@launch
                val cutoff = System.currentTimeMillis() - MAX_RETENTION_DAYS * 24 * 60 * 60 * 1000L
                for (f in files) {
                    if (f.lastModified() < cutoff) {
                        f.delete()
                    }
                }

                val zipDir = getZipDir(context)
                val zips = zipDir.listFiles() ?: return@launch
                for (z in zips) {
                    if (z.lastModified() < cutoff) {
                        z.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w("DiagnosticLogger", "Failed to prune old logs: ${e.message}")
            }
        }
    }

    /**
     * 读取最近的若干行日志用于前端预览
     */
    fun readRecentLogs(context: Context, maxLines: Int = 120): String {
        return try {
            val logDir = getLogDir(context)
            val files = logDir.listFiles { _, name -> name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            if (files.isEmpty()) {
                return "[暂无本地诊断日志记录]"
            }

            val lines = LinkedList<String>()
            for (file in files) {
                val fileLines = file.readLines()
                for (i in fileLines.indices.reversed()) {
                    lines.addFirst(fileLines[i])
                    if (lines.size >= maxLines) break
                }
                if (lines.size >= maxLines) break
            }

            lines.joinToString("\n")
        } catch (e: Exception) {
            "[读取日志异常: ${e.message}]"
        }
    }

    /**
     * 清空全部本地日志
     */
    fun clearAllLogs(context: Context): Boolean {
        return try {
            val logDir = getLogDir(context)
            logDir.listFiles()?.forEach { it.delete() }
            val zipDir = getZipDir(context)
            zipDir.listFiles()?.forEach { it.delete() }
            i(TAG_STATE, "All local diagnostic logs cleared by user")
            true
        } catch (e: Exception) {
            e(TAG_STATE, "Failed to clear logs", e)
            false
        }
    }

    /**
     * 打包现有日志为 Zip 文件，返回生成的 File 对象
     */
    fun packageLogsToZip(context: Context): File? {
        return try {
            val logDir = getLogDir(context)
            val files = logDir.listFiles { _, name -> name.endsWith(".log") } ?: emptyArray()
            if (files.isEmpty()) return null

            val zipDir = getZipDir(context)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val zipFile = File(zipDir, "octop_logs_${ts}.zip")

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                val buffer = ByteArray(4096)
                for (f in files) {
                    if (!f.exists() || f.length() == 0L) continue
                    val entry = ZipEntry(f.name)
                    zos.putNextEntry(entry)
                    FileInputStream(f).buffered().use { fis ->
                        var len: Int
                        while (fis.read(buffer).also { len = it } > 0) {
                            zos.write(buffer, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }
            zipFile
        } catch (e: Exception) {
            e(TAG_STATE, "Failed to package logs into zip", e)
            null
        }
    }
}
