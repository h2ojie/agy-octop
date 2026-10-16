# Issue 002: 聊天气泡显示原始结构块文本 `[{type=text, text=...}]` 与思考块未拆分

- **问题编号**：ISSUE-002
- **发现时间**：2026-10-14
- **修复时间**：2026-10-14
- **涉及模块**：Android 客户端 (`MessageParser.kt`, `ChatViewModel.kt`, `ChatBubble.kt`)
- **状态**：已解决 (Resolved)

---

## 一、 问题点 (Issue Phenomenon)

在 Android 客户端与专家对话（或加载历史会话）时，聊天界面气泡内展示了底层的原始块结构文本（如图）：
- **用户消息气泡**：展示为 `[{type=text, text=你好}]`，而非纯文本 `你好`；
- **AI 消息气泡**：展示为：
  ```
  [{type=thinking, thinking=Initial greeting received. Retrieving user context from memory...}, {type=text, text=你好！今天有什么想处理的，或者需要我做些什么？}]
  ```
- **副作用**：底层的深度思考卡片（`ThinkingBlock`）未能独立展开/折叠，而是连同内部调试信息与正文混为一谈，整体排版混乱。

---

## 二、 原因分析 (Root Cause Analysis)

### 1. 服务端消息格式为多模态 Content Block 列表

Octop 服务端（基于 LangGraph 状态机与 OpenAI / Anthropic 协议规范）在历史记录接口 `GET /api/agents/{agent_id}/threads/{thread_id}/history` 中，将消息的 `content` 序列化为结构化的 Block 列表：
```json
{
  "role": "assistant",
  "content": [
    {
      "type": "thinking",
      "thinking": "Initial greeting received. Retrieving user context from memory..."
    },
    {
      "type": "text",
      "text": "你好！今天有什么想处理的，或者需要我做些什么？"
    }
  ]
}
```

### 2. Retrofit + Gson 反序列化退化为 `ArrayList<LinkedTreeMap>`

在客户端原数据模型定义中：
```kotlin
data class HistoryMessageDto(
    val role: String? = null,
    val content: Any? = null,
    val thinking: String? = null
)
```
因为 `content` 被声明为了 `Any?`，Gson 遇到 JSON Array 时自动将其解析成了 Java 原生集合：`ArrayList<LinkedTreeMap<String, Any>>`。

### 3. 旧代码直接调用了 `content.toString()`

旧版 `ChatViewModel.kt` 的转换逻辑为：
```kotlin
val contentStr = when (val c = dto.content) {
    is String -> c
    else -> c?.toString() ?: ""
}
```
当 `c` 为 `ArrayList` 时，Kotlin 触发了 Java 集合的默认 `toString()` 实现：
- `ArrayList.toString()` 的输出格式正好是：`[{key1=val1, key2=val2}, ...]`！
- 这导致原本结构化的思考块与文本块，被硬编码拼接成了一个长字符串直接塞给了 `ChatMessage.content`，而 `ChatMessage.thinking` 保持为空。
- 最终在 `ChatBubble.kt` 渲染时，因为 `message.thinking.isEmpty()`，无法激活「💭 深度思考折叠卡片」；而 `message.content` 直接将该原始字符串打印在屏幕上。

---

## 三、 解决方案 (Fix & Implementation)

### 1. 新建专业级多模态块解析器 (`MessageParser.kt`)

为了保证无论是网络初次反序列化、本地字符串缓存、还是模型输出原生 `<think>` 标签，都能被无损分离，编写了通用的 `MessageParser`：

```kotlin
// com.octop.client.data.model.MessageParser.kt

object MessageParser {
    private val THINK_TAG_REGEX = Pattern.compile("<think>([\\s\\S]*?)</think>", Pattern.CASE_INSENSITIVE)
    private val JAVA_MAP_BLOCK_REGEX = Pattern.compile("\\{type=([a-zA-Z0-9_-]+),\\s*(text|thinking)=([\\s\\S]*?)\\}(?:,\\s*|$)")

    fun parse(content: Any?, fallbackThinking: String? = null): Pair<String, String> {
        val textParts = mutableListOf<String>()
        val thinkingParts = mutableListOf<String>()

        fallbackThinking?.trim()?.let { if (it.isNotEmpty()) thinkingParts.add(it) }

        when (content) {
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
                    }
                }
            }
            is String -> {
                val (t, th) = parseString(content)
                if (t.isNotEmpty()) textParts.add(t)
                if (th.isNotEmpty()) thinkingParts.add(th)
            }
            else -> if (content != null) {
                val (t, th) = parseString(content.toString())
                if (t.isNotEmpty()) textParts.add(t)
                if (th.isNotEmpty()) thinkingParts.add(th)
            }
        }

        return Pair(textParts.joinToString("\n").trim(), thinkingParts.joinToString("\n").trim())
    }

    fun parseString(raw: String): Pair<String, String> {
        var str = raw.trim()
        if (str.isEmpty()) return Pair("", "")

        val thinkingParts = mutableListOf<String>()
        val textParts = mutableListOf<String>()

        // 1. 处理模型自带的 <think>...</think> 标签
        val matcher = THINK_TAG_REGEX.matcher(str)
        val sb = StringBuffer()
        while (matcher.find()) {
            val think = matcher.group(1)?.trim() ?: ""
            if (think.isNotEmpty()) thinkingParts.add(think)
            matcher.appendReplacement(sb, "")
        }
        matcher.appendTail(sb)
        str = sb.toString().trim()

        // 2. 兼容被字符串化的 Java Map: [{type=..., text=...}, ...]
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
}
```

### 2. 在 `ChatViewModel.loadHistory` 中接入清洗逻辑

在将 DTO 映射为 UI 的 `ChatMessage` 时，调用 `MessageParser.parse` 统一分离：
```kotlin
val msgs = dtos.mapNotNull { dto ->
    val (textContent, thinkingContent) = MessageParser.parse(dto.content, dto.thinking)

    if (textContent.isBlank() && thinkingContent.isBlank()) {
        null
    } else {
        ChatMessage(
            isUser = dto.role == "user",
            content = textContent,
            thinking = thinkingContent,
            timestamp = if (dto.timestamp > 0) dto.timestamp else dto.created_at
        )
    }
}
```

### 3. 在 UI 气泡渲染层（`ChatBubble.kt`）添加二次防御

在 Composable 内部使用 `remember` 进行即时解包，即便存在由于先前版本产生的脏字符串，也能在渲染时自动恢复为优雅的 Markdown 气泡和思考折叠块：
```kotlin
val (cleanContent, cleanThinking) = remember(message.content, message.thinking) {
    val parsed = MessageParser.parseString(message.content)
    val finalThinking = if (message.thinking.isNotBlank()) message.thinking else parsed.second
    val finalContent = if (parsed.first.isNotBlank() || parsed.second.isNotBlank()) parsed.first else message.content
    Pair(finalContent, finalThinking)
}
```

---

## 四、 验证与结果 (Verification & Results)

1. **多场景解析单元测试**：
   - 输入 `[{type=text, text=你好}]` -> 成功提取 `content = "你好"`，`thinking = ""`；
   - 输入带 `thinking` 的复合数组 -> 成功分离 `content = "你好！今天有什么想处理的..."`，`thinking = "Initial greeting received..."`；
   - 输入原生 `<think>xxx</think>yyy` -> 成功抽离思考块 `xxx` 与正文 `yyy`。
2. **构建与部署**：
   - 重新执行 `./gradlew assembleDebug` 编译通过；
   - 通过 `scp` 重新部署到公网服务器 `/var/www/html/octop.apk`。
3. **渲染结果**：
   - 用户气泡不再出现 `[{type=text...}]`，仅展示绿色纯文字泡泡；
   - AI 气泡顶部展现「💭 已深度思考 (XX 字)」折叠卡片，可点击展开查看推理过程；下方干净展示 Markdown 回复正文。
