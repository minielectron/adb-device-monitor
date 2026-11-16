package devicemonitor.data.playerdebug

import java.net.URI
import java.security.MessageDigest

enum class RedactionCategory { SECRET, IDENTIFIER, URL }

data class RedactionResult<T>(val value: T, val counts: Map<RedactionCategory, Int>) {
    val total: Int get() = counts.values.sum()
}

object PlayerDebugRedactor {
    private const val REDACTED = "[REDACTED]"

    fun redactText(input: String): RedactionResult<String> {
        val counts = linkedMapOf<RedactionCategory, Int>()
        fun count(category: RedactionCategory) { counts[category] = (counts[category] ?: 0) + 1 }

        var output = URL.replace(input) { match ->
            val safe = safeUrl(match.value)
            if (safe != match.value) count(RedactionCategory.URL)
            safe
        }
        output = AUTHORIZATION.replace(output) { match ->
            count(RedactionCategory.SECRET)
            match.groupValues[1] + REDACTED
        }
        output = SENSITIVE_VALUE.replace(output) { match ->
            val key = match.groupValues[2]
            count(if (IDENTIFIER_KEY.matches(key)) RedactionCategory.IDENTIFIER else RedactionCategory.SECRET)
            match.groupValues[1] + REDACTED + match.groupValues[4]
        }
        return RedactionResult(output, counts)
    }

    fun redactAttributes(attributes: Map<String, String>): RedactionResult<Map<String, String>> {
        val output = linkedMapOf<String, String>()
        val counts = linkedMapOf<RedactionCategory, Int>()
        fun add(category: RedactionCategory, amount: Int = 1) {
            if (amount > 0) counts[category] = (counts[category] ?: 0) + amount
        }
        for ((key, value) in attributes) {
            if (SENSITIVE_KEY.matches(key)) {
                val category = if (IDENTIFIER_KEY.matches(key)) RedactionCategory.IDENTIFIER else RedactionCategory.SECRET
                output[key] = REDACTED
                add(category)
            } else {
                val result = redactText(value)
                output[key] = result.value
                result.counts.forEach { (category, amount) -> add(category, amount) }
            }
        }
        return RedactionResult(output, counts)
    }

    fun saltIdentifier(identifier: String, salt: String): String =
        "id_" + sha256("$salt\u0000$identifier").take(24)

    fun errorFingerprint(message: String, salt: String = ""): String {
        var normalized = redactText(message).value.lowercase()
        normalized = ISO_TIME.replace(normalized, "<time>")
        normalized = LOGCAT_TIME.replace(normalized, "<time>")
        normalized = UUID.replace(normalized, "<id>")
        normalized = HEX_ADDRESS.replace(normalized, "<addr>")
        normalized = LABELED_ID.replace(normalized) { it.groupValues[1] + "<id>" }
        normalized = LONG_NUMBER.replace(normalized, "<n>")
        normalized = WHITESPACE.replace(normalized, " ").trim()
        return sha256("$salt\u0000$normalized")
    }

    private fun safeUrl(raw: String): String = try {
        val clean = raw.trimEnd('.', ',', ';', ')', ']')
        val suffix = raw.substring(clean.length)
        val uri = URI(clean)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if ((scheme == "http" || scheme == "https") && !host.isNullOrBlank()) {
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            "$scheme://$host$port/[REDACTED]$suffix"
        } else REDACTED + suffix
    } catch (_: Exception) {
        REDACTED
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private val URL = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
    private val AUTHORIZATION = Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;]+")
    private val SENSITIVE_VALUE = Regex(
        "(?i)(([\\\"']?(?:access[_-]?token|refresh[_-]?token|token|cookie|license(?:[_-]?challenge)?|subscriber[_-]?id|device[_-]?id|content[_-]?id|session[_-]?id)[\\\"']?)\\s*[:=]\\s*[\\\"']?)([^\\\"'\\s,;}]+)([\\\"']?)"
    )
    private val SENSITIVE_KEY = Regex(
        "(?i)(?:authorization|access[_-]?token|refresh[_-]?token|token|cookie|license(?:[_-]?challenge)?|subscriber[_-]?id|device[_-]?id|content[_-]?id|session[_-]?id)"
    )
    private val IDENTIFIER_KEY = Regex("(?i)(?:subscriber|device|content|session)[_-]?id")
    private val ISO_TIME = Regex("\\b\\d{4}-\\d{2}-\\d{2}[t ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?z?\\b", RegexOption.IGNORE_CASE)
    private val LOGCAT_TIME = Regex("\\b\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b")
    private val UUID = Regex("\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b", RegexOption.IGNORE_CASE)
    private val HEX_ADDRESS = Regex("\\b0x[0-9a-f]+\\b", RegexOption.IGNORE_CASE)
    private val LABELED_ID = Regex("(?i)\\b((?:session|player|request|correlation)(?:[_ -]?id)?\\s*[:=]?\\s*)[a-z0-9_-]{4,}")
    private val LONG_NUMBER = Regex("\\b\\d{6,}\\b")
    private val WHITESPACE = Regex("\\s+")
}
