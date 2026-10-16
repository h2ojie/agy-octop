package com.octop.client.data.model

import com.google.gson.JsonParser
import java.util.regex.Pattern

object MessageParser {
    private val THINK_TAG_REGEX = Pattern.compile("<think>([\\s\\S]*?)</think>", Pattern.CASE_INSENSITIVE)
    private val JAVA_MAP_BLOCK_REGEX = Pattern.compile("\\{type=([a-zA-Z0-9_-]+),\\s*(text|thinking)=([\\s\\S]*?)\\}(?:,\\s*|$)")

    fun parse(content: Any?, fallbackThinking: String? = null): Pair<String, String> {
        val textParts = mutableListOf<String>()
        val thinkingParts = mutableListOf<String>()

        fallbackThinking?.trim()?.let {
            if (it.isNotEmpty()) thinkingParts.add(it)
        }

        when (content) {
            null -> {}
            is List<*> -> {
                for (item in content) {
                    when (item) {
                        is Map<*, *> -> {
                            val type = (item["type"] as? String)?.lowercase() ?: ""
                            val text = (item["text"] as? String)?.trim()
                            val thinking = ((item["thinking"] ?: item["reasoning"]) as? String)?.trim()
                            if (type in listOf("thinking", "reasoning") || !thinking.isNullOrEmpty()) {
                                val th = thinking ?: text ?: ""
                                if (th.isNotEmpty()) thinkingParts.add(th)
                            } else {
                                val t = text ?: thinking ?: ""
                                if (t.isNotEmpty()) textParts.add(t)
                            }
                        }
                        is String -> {
                            val (t, th) = parseString(item)
                            if (t.isNotEmpty()) textParts.add(t)
                            if (th.isNotEmpty()) thinkingParts.add(th)
                        }
                        else -> {
                            val (t, th) = parseString(item.toString())
                            if (t.isNotEmpty()) textParts.add(t)
                            if (th.isNotEmpty()) thinkingParts.add(th)
                        }
                    }
                }
            }
            is String -> {
                val (t, th) = parseString(content)
                if (t.isNotEmpty()) textParts.add(t)
                if (th.isNotEmpty()) thinkingParts.add(th)
            }
            else -> {
                val (t, th) = parseString(content.toString())
                if (t.isNotEmpty()) textParts.add(t)
                if (th.isNotEmpty()) thinkingParts.add(th)
            }
        }

        val fullText = textParts.joinToString("\n").trim()
        val fullThinking = thinkingParts.joinToString("\n").trim()
        return Pair(fullText, fullThinking)
    }

    fun parseString(raw: String): Pair<String, String> {
        var str = raw.trim()
        if (str.isEmpty()) return Pair("", "")

        val thinkingParts = mutableListOf<String>()
        val textParts = mutableListOf<String>()

        // 1. XML think tags <think>...</think>
        val matcher = THINK_TAG_REGEX.matcher(str)
        val sb = StringBuffer()
        while (matcher.find()) {
            val think = matcher.group(1)?.trim() ?: ""
            if (think.isNotEmpty()) thinkingParts.add(think)
            matcher.appendReplacement(sb, "")
        }
        matcher.appendTail(sb)
        str = sb.toString().trim()

        // 2. JSON array string: [{"type":"text", "text":"..."}, ...]
        if (str.startsWith("[") && str.endsWith("]")) {
            try {
                val element = JsonParser.parseString(str)
                if (element.isJsonArray) {
                    val array = element.asJsonArray
                    for (i in 0 until array.size()) {
                        val item = array.get(i)
                        if (item.isJsonObject) {
                            val obj = item.asJsonObject
                            val type = obj.get("type")?.asString?.lowercase() ?: ""
                            val text = obj.get("text")?.asString?.trim()
                            val thinking = (obj.get("thinking") ?: obj.get("reasoning"))?.asString?.trim()
                            if (type in listOf("thinking", "reasoning") || !thinking.isNullOrEmpty()) {
                                val th = thinking ?: text ?: ""
                                if (th.isNotEmpty()) thinkingParts.add(th)
                            } else {
                                val t = text ?: thinking ?: ""
                                if (t.isNotEmpty()) textParts.add(t)
                            }
                        } else if (item.isJsonPrimitive) {
                            textParts.add(item.asString.trim())
                        }
                    }
                    if (textParts.isNotEmpty() || thinkingParts.isNotEmpty()) {
                        return Pair(textParts.joinToString("\n"), thinkingParts.joinToString("\n"))
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Stringified Java Map / List: [{type=..., text=...}, ...]
        if (str.startsWith("[") && str.endsWith("]") && str.contains("type=")) {
            val inner = str.substring(1, str.length - 1).trim()
            val mapMatcher = JAVA_MAP_BLOCK_REGEX.matcher(inner)
            var found = false
            while (mapMatcher.find()) {
                found = true
                val btype = mapMatcher.group(1)?.lowercase() ?: ""
                val bfield = mapMatcher.group(2)?.lowercase() ?: ""
                val bcontent = mapMatcher.group(3)?.trim() ?: ""
                if (btype in listOf("thinking", "reasoning") || bfield == "thinking") {
                    if (bcontent.isNotEmpty()) thinkingParts.add(bcontent)
                } else {
                    if (bcontent.isNotEmpty()) textParts.add(bcontent)
                }
            }
            if (found && (textParts.isNotEmpty() || thinkingParts.isNotEmpty())) {
                return Pair(textParts.joinToString("\n"), thinkingParts.joinToString("\n"))
            }
        }

        return Pair(str, thinkingParts.joinToString("\n"))
    }

    fun parseAttachments(dto: HistoryMessageDto): List<AttachmentItem> {
        val list = mutableListOf<AttachmentItem>()
        val seenPaths = mutableSetOf<String>()

        fun addAttachment(
            workspacePath: String?,
            filename: String?,
            mimeType: String?,
            kind: String?
        ) {
            val cleanPath = workspacePath?.trim()?.removePrefix("/") ?: return
            if (cleanPath.isEmpty() || seenPaths.contains(cleanPath)) return
            seenPaths.add(cleanPath)

            val mime = mimeType?.trim()?.lowercase() ?: ""
            val name = filename?.trim()?.ifBlank { null }
                ?: cleanPath.substringAfterLast('/')
                ?: "attachment"
            val isImg = kind?.lowercase() == "image" ||
                mime.startsWith("image/") ||
                name.endsWith(".jpg", true) ||
                name.endsWith(".jpeg", true) ||
                name.endsWith(".png", true) ||
                name.endsWith(".webp", true) ||
                name.endsWith(".gif", true)

            list.add(
                AttachmentItem(
                    id = java.util.UUID.randomUUID().toString(),
                    localUriString = null,
                    filename = name,
                    mimeType = if (mime.isNotEmpty()) mime else if (isImg) "image/jpeg" else "application/octet-stream",
                    isImage = isImg,
                    status = AttachmentStatus.SUCCESS,
                    workspacePath = cleanPath,
                    previewUrl = null
                )
            )
        }

        // 1. 从顶层 inbound_attachments 提取
        dto.inboundAttachments?.forEach { map ->
            val path = (map["workspace_path"] ?: map["path"]) as? String
            val name = (map["filename"] ?: map["name"]) as? String
            val mime = (map["media_type"] ?: map["mime_type"]) as? String
            val kind = map["kind"] as? String
            addAttachment(path, name, mime, kind)
        }

        // 2. 从 content 内部的 Block 结构提取 (Map 列表)
        when (val c = dto.content) {
            is List<*> -> {
                for (item in c) {
                    if (item is Map<*, *>) {
                        val type = (item["type"] as? String)?.lowercase() ?: ""
                        if (type in listOf("image", "file", "image_url")) {
                            val path = (item["workspace_path"] ?: item["workspacePath"] ?: item["path"]) as? String
                            val name = (item["filename"] ?: item["name"]) as? String
                            val mime = (item["media_type"] ?: item["mime_type"]) as? String
                            addAttachment(path, name, mime, type)
                        }
                    }
                }
            }
            is String -> {
                if (c.trim().startsWith("[") && c.trim().endsWith("]")) {
                    try {
                        val elem = JsonParser.parseString(c)
                        if (elem.isJsonArray) {
                            for (sub in elem.asJsonArray) {
                                if (sub.isJsonObject) {
                                    val obj = sub.asJsonObject
                                    val type = obj.get("type")?.asString?.lowercase() ?: ""
                                    if (type in listOf("image", "file", "image_url")) {
                                        val path = (obj.get("workspace_path") ?: obj.get("path"))?.asString
                                        val name = (obj.get("filename") ?: obj.get("name"))?.asString
                                        val mime = (obj.get("media_type") ?: obj.get("mime_type"))?.asString
                                        addAttachment(path, name, mime, type)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return list
    }
}
