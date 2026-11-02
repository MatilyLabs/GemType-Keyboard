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
 * Scores the rewrite actions (Native script, English, Improve and the other rewrites) against real Gemini,
 * on `test/resources/gemtype/rewrite-cases.json`.
 * Skipped unless GEMTYPE_EVAL_KEY is set, so a normal test run never touches the network:
 *
 *     cd java && GEMTYPE_EVAL_KEY=... ./gradlew testOnlineDebugUnitTest --tests '*RewriteEval*' -i
 *
 * Optional: GEMTYPE_EVAL_MODEL (default [Choices.DEFAULT_MODEL]), GEMTYPE_EVAL_THINKING (MINIMAL, LOW, MEDIUM,
 * HIGH or NONE, or a comma-separated list such as MEDIUM,HIGH to compare: the whole set runs once per level;
 * default the app's [Choices.rewriteThinkingLevel]), GEMTYPE_EVAL_GAP_MS (pause between requests,
 * default 4000, to stay under the free tier's per-minute limit), GEMTYPE_EVAL_API_BASE (a mock server),
 * GEMTYPE_EVAL_CASES (another cases file). Prints a per-group table and the failures for each level, and writes
 * all of them to build/rewrite-eval.txt. The key is never printed or written.
 *
 * Each case goes through the app's own path, `GeminiClient.refine(text, action, config, target, thinkingLevel)` with
 * writing language "auto"; `target` is [Romanized.englishName] of the case's `romanized` language code (text typed
 * in English letters: every action but Native script then uses the two-field request, as the panel does), else of
 * its `target` code ([Action.NATIVE]).
 *
 * A case passes when all four hold: the script share (letters and marks in the case's `script`, ignoring the
 * `keep` items) is at least [MIN_SCRIPT_SHARE]; every `mustContain` is in the output (case-insensitive); no
 * `mustNotContain` is (case-insensitive; Latin entries match whole words only); every `keep` is in the output
 * unchanged. `expected` is one good answer for the report, not a required match.
 */
class RewriteEval {
    private class Case(
        val id: String, val group: String, val action: Action, val target: String?, val romanized: String?,
        val text: String, val expected: String, val mustContain: List<String>, val mustNotContain: List<String>, val script: Character.UnicodeScript,
        val keep: List<String>, val note: String,
    )

    private class Result(val case: Case, val output: String?, val readAs: String?, val error: String?, val ms: Long) {
        val share: Double = if (output == null) 0.0 else scriptShare(output, case.script, case.keep)
        val scriptOk = output != null && share >= MIN_SCRIPT_SHARE
        // Bangla ড় য় and Hindi क़ ड़ have two encodings that look the same; compare in one (NFC).
        private val out = output?.let(::nfc)
        val missing = if (out == null) case.mustContain else case.mustContain.filter { !out.contains(nfc(it), ignoreCase = true) }
        val forbidden = if (out == null) emptyList() else case.mustNotContain.filter { containsForbidden(out, nfc(it)) }
        val lostKeep = if (out == null) case.keep else case.keep.filter { !out.contains(nfc(it)) }
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
                o.optString("target").ifBlank { null }, o.optString("romanized").ifBlank { null }, o.getString("text"), o.optString("expected"),
                strings(o.optJSONArray("mustContain")), strings(o.optJSONArray("mustNotContain")),
                Character.UnicodeScript.forName(o.getString("script")), strings(o.optJSONArray("keep")), o.optString("note"),
            )
        }
    }

    @Test fun rewriteAgainstGemini() {
        val key = System.getenv("GEMTYPE_EVAL_KEY").orEmpty()
        assumeTrue("set GEMTYPE_EVAL_KEY to run the rewrite eval", key.isNotBlank())
        // GEMTYPE_EVAL_ONLY: a regex on case ids, to rerun part of the set (e.g. "^(ur|ta)-|-(ur|ta)-1$").
        val only = System.getenv("GEMTYPE_EVAL_ONLY")?.takeIf { it.isNotBlank() }?.toRegex()
        val cases = loadCases()?.filter { only == null || only.containsMatchIn(it.id) }
        assumeTrue("no rewrite-cases.json found", cases != null)
        cases!!

        val model = System.getenv("GEMTYPE_EVAL_MODEL")?.takeIf { it.isNotBlank() } ?: Choices.DEFAULT_MODEL
        val base = System.getenv("GEMTYPE_EVAL_API_BASE")?.takeIf { it.isNotBlank() }
            ?: "https://generativelanguage.googleapis.com/v1beta/models"
        val client = GeminiClient(HttpTransport(base))
        // A comma-separated list runs the whole set once per level, to compare quality and speed.
        val levels = System.getenv("GEMTYPE_EVAL_THINKING")?.takeIf { it.isNotBlank() }?.split(',')
            ?.map { it.trim().uppercase() }?.map { if (it == "NONE") null else it } ?: listOf(Choices.rewriteThinkingLevel(model))
        val gapMs = System.getenv("GEMTYPE_EVAL_GAP_MS")?.toLongOrNull() ?: 4_000L
        val config = GemtypeConfig(key, model, Choices.AUTO_LANGUAGE)

        val reports = levels.map { thinking ->
            val results = cases.mapIndexed { i, c ->
                if (i > 0) runBlocking { delay(gapMs) }
                val target = (c.romanized ?: c.target)?.let { Romanized.englishName(it) }
                var output: String? = null
                var readAs: String? = null
                var error: String? = null
                var ms = 0L
                // Patient retries on a rate limit (not timed); anything else is recorded as the case's error.
                for (attempt in 1..4) {
                    val start = System.nanoTime()
                    try {
                        val rewrite = runBlocking { client.refine(c.text, c.action, config, target, thinking) }
                        output = rewrite.text
                        readAs = rewrite.readAs
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
                Result(c, output, readAs, error, ms)
            }
            report("$model, thinking ${thinking ?: "none"}", results).also { println(it) }
        }
        File("build").mkdirs()
        File("build/rewrite-eval.txt").writeText(reports.joinToString("\n" + "=".repeat(60) + "\n\n"))
        assertTrue(reports.isNotEmpty())
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
            sb.append("\n[${r.case.id}] ${r.case.action.name.lowercase()} ${r.case.romanized ?: r.case.target ?: ""}: ${r.case.note}\n")
            sb.append("  text:     ${r.case.text}\n")
            if (r.error != null) {
                sb.append("  error:    ${r.error}\n")
                continue
            }
            sb.append("  got:      ${r.output}\n")
            if (r.readAs != null) sb.append("  read as:  ${r.readAs}\n")
            sb.append("  expected: ${r.case.expected}\n")
            if (!r.scriptOk) sb.append("  script:   ${(r.share * 100).toInt()}% ${r.case.script.name.lowercase()}, need ${(MIN_SCRIPT_SHARE * 100).toInt()}%\n")
            if (r.missing.isNotEmpty()) sb.append("  missing:  ${r.missing}\n")
            if (r.forbidden.isNotEmpty()) sb.append("  forbidden present: ${r.forbidden}\n")
            if (r.lostKeep.isNotEmpty()) sb.append("  changed text that must stay: ${r.lostKeep}\n")
        }
        // Every answer, so a person (or Claude) can judge quality beyond the automatic checks.
        sb.append("\nAll outputs\n")
        for (r in results) {
            sb.append("\n[${r.case.id}] ${if (r.pass) "pass" else "FAIL"}\n  text: ${r.case.text}\n  got:  ${r.output ?: r.error}\n")
            if (r.readAs != null) sb.append("  read as: ${r.readAs}\n")
        }
        return sb.toString()
    }

    companion object {
        const val MIN_SCRIPT_SHARE = 0.85

        /** Share of [text]'s letters (and their combining marks) written in [script], leaving out the [keep] items. */
        fun nfc(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)

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
