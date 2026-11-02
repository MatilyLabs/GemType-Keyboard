// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Scores the proofreader against real Gemini on `test/resources/gemtype/proofread-cases.json`.
 * Skipped unless GEMTYPE_EVAL_KEY is set, so a normal test run never touches the network:
 *
 *     cd java && GEMTYPE_EVAL_KEY=... ./gradlew testOnlineDebugUnitTest --tests '*ProofreadEval*' -i
 *
 * Optional: GEMTYPE_EVAL_MODEL (default [Choices.DEFAULT_MODEL]), GEMTYPE_EVAL_API_BASE (a mock server),
 * GEMTYPE_EVAL_CASES (another cases file). Prints a per-language table and the failures, and writes
 * build/proofread-eval.txt. The key is never printed or written.
 *
 * Like the keyboard's default, every case runs with writing language "auto" plus the case's keyboard
 * language as the hint; a case's `lang` is only its group in the table.
 *
 * Per case: "caught" = every mustChange text sits inside some correction's original; "kept" = no
 * correction overlaps any occurrence of a mustKeep text; "exact" = applying all corrections gives `fixed`.
 * A case passes when it is caught and kept; exact is reported on its own (there can be several good answers).
 */
class ProofreadEval {
    private class Case(
        val id: String, val lang: String, val keyboard: String?, val text: String, val fixed: String,
        val mustChange: List<String>, val mustKeep: List<String>, val note: String,
    )

    private class Result(val case: Case, val corrections: List<Correction>, val error: String?, val ms: Long) {
        val caught = error == null && case.mustChange.all { m -> corrections.any { it.original.contains(m) } }
        val kept = error == null && case.mustKeep.none { k -> occurrences(case.text, k).any { s -> corrections.any { s < it.end && s + k.length > it.start } } }
        val exact = error == null && Corrections.apply(case.text, corrections) == case.fixed
        val pass get() = caught && kept
    }

    private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun loadCases(): List<Case>? {
        val path = System.getenv("GEMTYPE_EVAL_CASES")?.takeIf { it.isNotBlank() }
        val json = if (path != null) File(path).takeIf { it.exists() }?.readText()
        else javaClass.getResourceAsStream("/gemtype/proofread-cases.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (json == null) return null
        val array = JSONArray(json)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            Case(
                o.getString("id"), o.optString("lang", Choices.AUTO_LANGUAGE), o.optString("keyboard").ifBlank { null },
                o.getString("text"), o.optString("fixed", o.getString("text")),
                strings(o.optJSONArray("mustChange")), strings(o.optJSONArray("mustKeep")), o.optString("note"),
            )
        }
    }

    @Test fun proofreadAgainstGemini() {
        val key = System.getenv("GEMTYPE_EVAL_KEY").orEmpty()
        assumeTrue("set GEMTYPE_EVAL_KEY to run the proofreader eval", key.isNotBlank())
        val cases = loadCases()
        assumeTrue("no proofread-cases.json found", cases != null)
        cases!!

        val model = System.getenv("GEMTYPE_EVAL_MODEL")?.takeIf { it.isNotBlank() } ?: Choices.DEFAULT_MODEL
        val base = System.getenv("GEMTYPE_EVAL_API_BASE")?.takeIf { it.isNotBlank() }
            ?: "https://generativelanguage.googleapis.com/v1beta/models"
        val client = GeminiClient(HttpTransport(base))

        val results = cases.map { c ->
            val config = GemtypeConfig(key, model, Choices.AUTO_LANGUAGE)
            val start = System.nanoTime()
            var error: String? = null
            var corrections = emptyList<Correction>()
            // One patient retry on a rate limit; anything else is recorded as the case's error.
            for (attempt in 1..2) {
                try {
                    corrections = runBlocking { client.check(c.text, config, c.keyboard) }
                    error = null
                    break
                } catch (e: GeminiException) {
                    error = "${e.kind}: ${e.message}"
                    if (e.kind != GeminiException.Kind.RATE_LIMITED) break
                    runBlocking { delay(20_000) }
                }
            }
            Result(c, corrections, error, (System.nanoTime() - start) / 1_000_000)
        }
        val report = report(model, results)
        println(report)
        File("build").mkdirs()
        File("build/proofread-eval.txt").writeText(report)
        assertTrue(results.isNotEmpty())
    }

    private fun report(model: String, results: List<Result>): String {
        val sb = StringBuilder("Proofreader eval, model $model, ${results.size} cases\n\n")
        sb.append("%-14s %5s %7s %6s %6s %8s\n".format("language", "cases", "caught", "kept", "exact", "avg ms"))
        fun row(name: String, rs: List<Result>) = sb.append(
            "%-14s %5d %7s %6s %6s %8d\n".format(
                name, rs.size, "${rs.count { it.caught }}/${rs.size}", "${rs.count { it.kept }}/${rs.size}",
                "${rs.count { it.exact }}/${rs.size}", rs.map { it.ms }.average().toLong(),
            ),
        )
        results.groupBy { it.case.lang }.toSortedMap().forEach { (lang, rs) -> row(lang, rs) }
        row("ALL", results)
        sb.append("\npassed ${results.count { it.pass }}/${results.size} (caught and kept)\n")
        val failures = results.filter { !it.pass || it.error != null }
        if (failures.isNotEmpty()) sb.append("\nFailures\n")
        for (r in failures) {
            sb.append("\n[${r.case.id}] ${r.case.lang}: ${r.case.note}\n  text:  ${r.case.text}\n")
            if (r.error != null) sb.append("  error: ${r.error}\n")
            if (!r.caught && r.error == null) sb.append("  missed: ${r.case.mustChange.filter { m -> r.corrections.none { it.original.contains(m) } }}\n")
            if (!r.kept) sb.append("  touched text that must stay\n")
            for (c in r.corrections) sb.append("  got:   \"${c.original}\" -> \"${c.replacement}\" (${c.type.name.lowercase()}: ${c.explanation})\n")
        }
        val inexact = results.filter { it.pass && !it.exact }
        if (inexact.isNotEmpty()) {
            sb.append("\nPassed but not identical to the expected text (may still be fine)\n")
            for (r in inexact) sb.append("  [${r.case.id}] got: ${Corrections.apply(r.case.text, r.corrections)}\n         want: ${r.case.fixed}\n")
        }
        return sb.toString()
    }

    private companion object {
        fun occurrences(text: String, part: String): List<Int> {
            if (part.isEmpty()) return emptyList()
            val found = ArrayList<Int>()
            var i = text.indexOf(part)
            while (i != -1) { found.add(i); i = text.indexOf(part, i + 1) }
            return found
        }
    }
}
