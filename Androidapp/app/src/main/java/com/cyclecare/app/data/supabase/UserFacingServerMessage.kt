package com.cyclecare.app.data.supabase

internal object UserFacingServerMessage {
    private val implementationDetails = Regex(
        "(?i)\\b(?:api(?:[ _-]?key)?|database|backend|supabase|postgrest|postgres(?:ql)?|sqlstate|http(?:[ _-]?status)?\\s*[45]\\d\\d|internal server error|edge function|rpc|jwt|bearer token|schema|relation|column|constraint|stack trace|exception|traceback|permission denied|invalid input syntax)\\b"
    )

    fun sanitize(value: String?, fallback: String): String {
        val candidate = value.orEmpty().replace(Regex("[\\r\\n\\t]+"), " ").trim()
        if (candidate.isBlank() || candidate.length > 240) return fallback
        if (candidate.startsWith('{') || candidate.startsWith('[') || candidate.startsWith('<')) return fallback
        if (implementationDetails.containsMatchIn(candidate)) return fallback
        if (candidate.any { it.code < 0x20 }) return fallback
        return candidate
    }
}
