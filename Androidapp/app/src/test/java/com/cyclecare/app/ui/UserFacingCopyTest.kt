package com.cyclecare.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Machinery must never reach the member's screen (Roy's UI principle:
 * "users never see backend mechanics"). This test walks every string literal
 * in the user-facing layer and fails on banned infrastructure, provider, or
 * protocol wording, so a future change cannot re-leak it.
 *
 * Scanned: the ui tree, MainActivity, and notification display models. HTTP plumbing
 * (clients, headers, URLs) never renders and is deliberately out of scope.
 * Not scanned: data/domain plumbing (headers, URLs, payload keys), which never
 * renders; anything shown from those layers must pass through
 * UserFacingServerMessage first.
 */
class UserFacingCopyTest {

    private val banned = Regex(
        "(?i)\\b(" +
            // infra + providers
            "supabase|gemini|openrouter|cohere|pollinations|deepinfra|huggingface|okx|payhero|nexus|acdc" +
            "|edge function|rpc|postgrest|postgres(?:ql)?|sqlstate|jwt|api[ _-]?key|bearer|webhook" +
            // protocol / transport
            "|stk push|sync(?:ed|ing)?\\b|revision\\b|payload\\b|malformed|deployed\\b" +
            // AI machinery
            "|artificial intelligence|language model|llm|chatbot|\\bmodel\\b" +
            // dev jargon
            "|stack ?trace|exception|uuid|debug\\b" +
            ")|\\bAI\\b|\\b[A-F0-9]{8}-[A-F0-9]{4}-[A-F0-9]{4}-" // uppercase AI word + UUID prefix
    )

    /** String literals that look like prose (what a member can read), not keys. */
    private val literal = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")

    /** Internal provider identifiers used as map keys only; never rendered. */
    private val allowedOccurrences = setOf(
        "ui/viewmodel/CycleCareViewModel.kt:COHERE",
        "ui/viewmodel/CycleCareViewModel.kt:GEMINI",
        "ui/viewmodel/CycleCareViewModel.kt:OPENROUTER"
    )

    private val scannedRoots = listOf(
        "src/main/java/com/cyclecare/app/ui",
        "src/main/java/com/cyclecare/app/data/notification/NotificationModels.kt",
        "src/main/java/com/cyclecare/app/MainActivity.kt"
    )

    @Test
    fun `no user-visible string leaks backend machinery`() {
        val violations = mutableListOf<String>()
        val files = scannedRoots.flatMap { root ->
            val f = File(root)
            if (f.isFile) listOf(f)
            else f.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue("expected to scan ui sources, found none", files.size > 20)
        for (file in files) {
            val rel = file.path.substringAfter("com/cyclecare/app/")
            val text = file.readText()
            for (match in literal.findAll(text)) {
                val s = match.groupValues[1]
                if (s.length < 3) continue
                // skip non-prose identifiers: testTags, keys, mime types, routes
                if (Regex("^[a-z0-9_./:-]+$").matches(s) && ' ' !in s) continue
                if (banned.containsMatchIn(s) && "$rel:$s" !in allowedOccurrences) {
                    violations += "$rel: \"$s\""
                }
            }
        }
        assertTrue(
            "User-visible copy leaks machinery:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
