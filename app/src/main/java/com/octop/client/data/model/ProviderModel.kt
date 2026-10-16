package com.octop.client.data.model

import com.google.gson.annotations.SerializedName

data class ReasoningConfig(
    val supported: Boolean = false,
    val toggle: Boolean = false,
    @SerializedName("default_mode") val defaultMode: String? = "enabled",
    val efforts: List<String> = emptyList(),
    @SerializedName("default_effort") val defaultEffort: String? = null
)

data class ResolvedModel(
    @SerializedName("provider_id") val providerId: Long = 0,
    @SerializedName("provider_name") val providerName: String = "",
    @SerializedName("provider_kind") val providerKind: String = "",
    val model: String = "",
    val name: String = "",
    val reasoning: Boolean = false,
    @SerializedName("reasoning_config") val reasoningConfig: ReasoningConfig? = null
) {
    val modelRef: String
        get() = if (providerName.isNotBlank()) "$providerName/$model" else model

    val displayName: String
        get() = formatModelName(name.ifBlank { model })

    val efforts: List<String>
        get() = reasoningConfig?.efforts ?: if (reasoning) listOf("high") else emptyList()

    val defaultEffort: String
        get() = reasoningConfig?.defaultEffort ?: efforts.firstOrNull() ?: "high"

    val fullDisplayName: String
        get() = if (providerName.isNotBlank()) "$providerName / $displayName" else displayName

    companion object {
        fun extractProvider(raw: String): String {
            if (raw.isBlank()) return ""
            val trimmed = raw.trim()
            return when {
                trimmed.contains("/") -> trimmed.substringBefore("/").trim()
                trimmed.contains(" · ") -> trimmed.substringBefore(" · ").trim()
                trimmed.endsWith("-cli") -> "cli"
                else -> ""
            }
        }

        fun formatModelName(raw: String): String {
            if (raw.isBlank()) return ""
            val clean = raw.trim().removePrefix("cli/").removePrefix("ice/").removeSuffix("-cli")
            val lower = clean.lowercase()
            return when {
                lower.startsWith("gemini-3.8-flash") || lower.startsWith("gemini 3.8 flash") -> "Gemini 3.8 Flash"
                lower.startsWith("gemini-3.7-flash") || lower.startsWith("gemini 3.7 flash") -> "Gemini 3.7 Flash"
                lower.startsWith("gemini-3") || lower.startsWith("gemini 3") -> "Gemini 3"
                lower.startsWith("gpt-6.1-sol") || lower.startsWith("gpt 6.1 sol") || lower.startsWith("gpt-6.1 sol") -> "GPT-6.1 Sol"
                lower.startsWith("gpt-6-astra") || lower.startsWith("gpt 6 astra") -> "GPT-6 Astra"
                lower.startsWith("gpt-6-luna") || lower.startsWith("gpt 6 luna") -> "GPT-6 Luna"
                lower.startsWith("gpt-6-sol") || lower.startsWith("gpt 6 sol") -> "GPT-6 Sol"
                lower.startsWith("claude-opus-5-5") || lower.startsWith("claude-opus-5.5") || lower.startsWith("claude opus 5.5") -> "Claude Opus 5.5"
                lower.startsWith("claude-sonnet-5") || lower.startsWith("claude sonnet 5") -> "Claude Sonnet 5"
                lower.startsWith("claude-opus-4-6") || lower.startsWith("claude-opus-4.6") || lower.startsWith("claude opus 4.6") -> "Claude Opus 4.6"
                lower.startsWith("claude-sonnet-4-6") || lower.startsWith("claude-sonnet-4.6") || lower.startsWith("claude sonnet 4.6") -> "Claude Sonnet 4.6"
                lower.startsWith("grok-4.7") || lower.startsWith("grok 4.7") -> "Grok 4.7"
                lower.startsWith("grok-4.6") || lower.startsWith("grok 4.6") -> "Grok 4.6"
                lower.startsWith("gpt-oss-120b") || lower.startsWith("gpt oss 120b") -> "GPT-OSS 120B"
                else -> clean.replace("-", " ")
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            }
        }

        fun formatModelWithProvider(raw: String, provider: String? = null): String {
            if (raw.isBlank()) return ""
            val prov = provider?.takeIf { it.isNotBlank() } ?: extractProvider(raw)
            val name = formatModelName(raw)
            return if (prov.isNotBlank()) "$prov / $name" else name
        }

        fun formatEffortName(effort: String): String {
            return when (effort.lowercase().trim()) {
                "low" -> "Low"
                "medium" -> "Medium"
                "high" -> "High"
                "xhigh", "extra-high", "extra_high" -> "Extra High"
                else -> effort.replaceFirstChar { it.uppercase() }
            }
        }
    }
}

val DEFAULT_RESOLVED_MODELS = listOf(
    ResolvedModel(
        providerName = "cli",
        model = "gemini-3.8-flash-high",
        name = "gemini-3.8-flash-high",
        reasoning = true,
        reasoningConfig = ReasoningConfig(supported = true, efforts = listOf("high"), defaultEffort = "high")
    ),
    ResolvedModel(
        providerName = "ice",
        model = "gpt-6.1-sol",
        name = "gpt-6.1-sol",
        reasoning = true,
        reasoningConfig = ReasoningConfig(supported = true, efforts = listOf("low", "medium", "high", "xhigh"), defaultEffort = "high")
    ),
    ResolvedModel(
        providerName = "cli",
        model = "claude-opus-5-5",
        name = "claude-opus-5-5",
        reasoning = true,
        reasoningConfig = ReasoningConfig(supported = true, efforts = listOf("low", "medium", "high", "xhigh"), defaultEffort = "high")
    ),
    ResolvedModel(
        providerName = "cli",
        model = "grok-4.7",
        name = "grok-4.7",
        reasoning = true,
        reasoningConfig = ReasoningConfig(supported = true, efforts = listOf("high", "xhigh"), defaultEffort = "high")
    ),
    ResolvedModel(
        providerName = "ice",
        model = "gpt-6-astra",
        name = "gpt-6-astra",
        reasoning = true,
        reasoningConfig = ReasoningConfig(supported = true, efforts = listOf("low", "medium", "high", "xhigh"), defaultEffort = "high")
    ),
    ResolvedModel(
        providerName = "ice",
        model = "gpt-6-luna",
        name = "gpt-6-luna",
        reasoning = true,
        reasoningConfig = ReasoningConfig(supported = true, efforts = listOf("medium", "high", "xhigh"), defaultEffort = "high")
    )
)
