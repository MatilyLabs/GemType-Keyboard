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
 * Scores the rewrite actions that change script or language ([Action.NATIVE], [Action.ENGLISH]) and the
 * plain [Action.FIX] on non-English text, against real Gemini, on `test/resources/gemtype/rewrite-cases.json`.
 * Skipped unless GEMTYPE_EVAL_KEY is set, so a normal test run never touches the network:
 *
 *     cd java && GEMTYPE_EVAL_KEY=... ./gradlew testOnlineDebugUnitTest --tests '*RewriteEval*' -i
 *
 * Optional: GEMTYPE_EVAL_MODEL (default [Choices.DEFAULT_MODEL]), GEMTYPE_EVAL_GAP_MS (pause between requests,
 * default 4000, to stay under the free tier's per-minute limit), GEMTYPE_EVAL_API_BASE (a mock server),
 * GEMTYPE_EVAL_CASES (another cases file). Prints a per-group table and the failures, and writes
 * build/rewrite-eval.txt. The key is never printed or written.
 *
 * Each case goes through the app's own path, `GeminiClient.refine(text, action, config, target)` with writing
 * language "auto"; `target` is [Romanized.englishName] of the case's language code, as the app passes it.
 *
 * A case passes when all four hold: the script share (letters and marks in the case's `script`, ignoring the
 * `keep` items) is at least [MIN_SCRIPT_SHARE]; every `mustContain` is in the output (case-insensitive); no
 * `mustNotContain` is (case-insensitive; Latin entries match whole words only); every `keep` is in the output
 * unchanged. `expected` is one good answer for the report, not a required match.
 */
class RewriteEval {
    private class Case(
        val id: String, val group: String, val action: Action, val target: String?, val text: String, val expected: String,
        val mustContain: List<String>, val mustNotContain: List<String>, val script: Character.UnicodeScript,
        val keep: List<String>, val note: String,
    )

    private class Result(val case: Case, val output: String?, val error: String?, val ms: Long) {
        val share: Double = if (output == null) 0.0 else scriptShare(output, case.script, case.keep)
        val scriptOk = output != null && share >= MIN_SCRIPT_SHARE
        val missing = if (output == null) case.mustContain else case.mustContain.filter { !output.contains(it, ignoreCase = true) }
        val forbidden = if (output == null) emptyList() else case.mustNotContain.filter { containsForbidden(output, it) }
        val lostKeep = if (output == null) case.keep else case.keep.filter { !output.contains(it) }
        val pass get() = error == null && scriptOk && missing.isEmpty() && forbidden.isEmpty() && lostKeep.isEmpty()
    }

    private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun loadCases(): List<Case>? {
        val path = System.getenv("GEMTYPE_EVAL_CASES")?.takeIf { it.isNotBlank() }
        val json = if (path != null) File(path).takeIf { it.exists() }?.readText()
        else javaClass.getResourceAsStream("/gemtype/rewrite-cases.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (json == null) return null
        val array = JSONArray(json)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            Case(
                o.getString("id"), o.getString("group"), Action.valueOf(o.getString("action")),
                o.optString("target").ifBlank { null }, o.getString("text"), o.optString("expected"),
                strings(o.optJSONArray("mustContain")), strings(o.optJSONArray("mustNotContain")),
                Character.UnicodeScript.forName(o.getString("script")), strings(o.optJSONArray("keep")), o.optString("note"),
            )
        }
    }

    @Test fun rewriteAgainstGemini() {
        val key = System.getenv("GEMTYPE_EVAL_KEY").orEmpty()
        assumeTrue("set GEMTYPE_EVAL_KEY to run the rewrite eval", key.isNotBlank())
        val cases = loadCases()
        assumeTrue("no rewrite-cases.json found", cases != null)
        cases!!

        val model = System.getenv("GEMTYPE_EVAL_MODEL")?.takeIf { it.isNotBlank() } ?: Choices.DEFAULT_MODEL
        val base = System.getenv("GEMTYPE_EVAL_API_BASE")?.takeIf { it.isNotBlank() }
            ?: "https://generativelanguage.googleapis.com/v1beta/models"
        val client = GeminiClient(HttpTransport(base))
        val gapMs = System.getenv("GEMTYPE_EVAL_GAP_MS")?.toLongOrNull() ?: 4_000L
        val config = GemtypeConfig(key, model, Choices.AUTO_LANGUAGE)

        val results = cases.mapIndexed { i, c ->
            if (i > 0) runBlocking { delay(gapMs) }
            val target = c.target?.let { Romanized.englishName(it) }
            var output: String? = null
            var error: String? = null
            var ms = 0L
            // Patient retries on a rate limit (not timed); anything else is recorded as the case's error.
            for (attempt in 1..4) {
                val start = System.nanoTime()
                try {
                    output = runBlocking { client.refine(c.text, c.action, config, target) }
                    error = null
                    ms = (System.nanoTime() - start) / 1_000_000
                    break
                } catch (e: GeminiException) {
                    error = "${e.kind}: ${e.message}"
                    ms = (System.nanoTime() - start) / 1_000_000
                    if (e.kind != GeminiException.Kind.RATE_LIMITED) break
                    runBlocking { delay(30_000L * attempt) }
                }
            }
            Result(c, output, error, ms)
        }
        val report = report(model, results)
        println(report)
        File("build").mkdirs()
        File("build/rewrite-eval.txt").writeText(report)
        assertTrue(results.isNotEmpty())
    }

    private fun report(model: String, results: List<Result>): String {
        val sb = StringBuilder("Rewrite eval, model $model, ${results.size} cases\n\n")
        sb.append("%-16s %5s %7s %9s %8s\n".format("group", "cases", "passed", "script %", "avg ms"))
        fun row(name: String, rs: List<Result>) = sb.append(
            "%-16s %5d %7s %9d %8d\n".format(
                name, rs.size, "${rs.count { it.pass }}/${rs.size}",
                (rs.map { it.share }.average() * 100).toInt(), rs.map { it.ms }.average().toLong(),
            ),
        )
        results.groupBy { it.case.group }.toSortedMap().forEach { (group, rs) -> row(group, rs) }
        row("ALL", results)
        sb.append("\npassed ${results.count { it.pass }}/${results.size} (script, mustContain, mustNotContain, keep)\n")
        val failures = results.filter { !it.pass }
        if (failures.isNotEmpty()) sb.append("\nFailures\n")
        for (r in failures) {
            sb.append("\n[${r.case.id}] ${r.case.action.name.lowercase()} ${r.case.target ?: ""}: ${r.case.note}\n")
            sb.append("  text:     ${r.case.text}\n")
            if (r.error != null) {
                sb.append("  error:    ${r.error}\n")
                continue
            }
            sb.append("  got:      ${r.output}\n  expected: ${r.case.expected}\n")
            if (!r.scriptOk) sb.append("  script:   ${(r.share * 100).toInt()}% ${r.case.script.name.lowercase()}, need ${(MIN_SCRIPT_SHARE * 100).toInt()}%\n")
            if (r.missing.isNotEmpty()) sb.append("  missing:  ${r.missing}\n")
            if (r.forbidden.isNotEmpty()) sb.append("  forbidden present: ${r.forbidden}\n")
            if (r.lostKeep.isNotEmpty()) sb.append("  changed text that must stay: ${r.lostKeep}\n")
        }
        return sb.toString()
    }

    companion object {
        const val MIN_SCRIPT_SHARE = 0.85

        /** Share of [text]'s letters (and their combining marks) written in [script], leaving out the [keep] items. */
        fun scriptShare(text: String, script: Character.UnicodeScript, keep: List<String>): Double {
            var rest = text
            for (k in keep) rest = rest.replace(k, " ")
            var total = 0
            var inScript = 0
            var i = 0
            while (i < rest.length) {
                val cp = rest.codePointAt(i)
                i += Character.charCount(cp)
                val type = Character.getType(cp)
                val isMark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
                if (!Character.isLetter(cp) && !isMark) continue
                val s = Character.UnicodeScript.of(cp)
                if (s == Character.UnicodeScript.INHERITED || s == Character.UnicodeScript.COMMON) continue
                total++
                if (s == script) inScript++
            }
            return if (total == 0) 1.0 else inScript.toDouble() / total
        }

        /** Latin-letter entries match whole words only ("kore" must not hit "score"); anything else is a plain substring. */
        fun containsForbidden(output: String, entry: String): Boolean =
            if (entry.all { it.code < 128 }) Regex("(?<![A-Za-z])" + Regex.escape(entry) + "(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(output)
            else output.contains(entry, ignoreCase = true)
    }
}
