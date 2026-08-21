package com.cyma.videoloop.data.template

/**
 * One style rule lifted out of a stylesheet.
 *
 * [atPrelude] is the enclosing conditional at-rule (`@media (...)`, `@supports (...)`)
 * or null at top level. Emitted compat rules have to be re-wrapped in it, otherwise a
 * rule that only applies inside a media query would leak out of it.
 *
 * [declarations] keeps source order with `!important` stripped from the value. Nothing
 * downstream needs importance: the shim emits `!important` on everything it writes and
 * is injected last in `<head>`, so it wins every tie regardless.
 */
internal data class CssRule(
    val atPrelude: String?,
    val selector: String,
    val declarations: List<Pair<String, String>>,
) {
    /** Last value wins, mirroring the cascade within a single block. */
    fun value(property: String): String? =
        declarations.lastOrNull { it.first == property }?.second
}

/**
 * A brace-depth CSS scanner — deliberately not a regex.
 *
 * Production template stylesheets contain every trap a flat regex falls into:
 * commented-out declaration blocks (a regex grabbing "the selector before
 * `text-decoration-color`" walks straight into comment text), `radial-gradient(#2f2b2a
 * 0.2vh, transparent 0)` (nested parens holding commas), `@font-face` with a two-URL
 * `src`, `@keyframes`/`@-webkit-keyframes` with nested blocks, and selector lists split
 * across lines.
 *
 * Pure: no Android imports, no I/O, no logging — so it is unit-testable on the JVM.
 */
internal object CssRuleScanner {

    /** At-rules whose body holds style rules that still apply, conditionally. */
    private val CONDITIONAL_AT_RULES = setOf("@media", "@supports", "@document", "@-moz-document", "@layer")

    /**
     * Strips CSS comments, honouring string literals so a comment opener inside a
     * quoted value survives. An unterminated comment runs to end of input, like a real
     * CSS parser.
     */
    fun stripComments(css: String): String {
        val out = StringBuilder(css.length)
        var i = 0
        var quote: Char? = null
        while (i < css.length) {
            val c = css[i]
            when {
                quote != null -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < css.length) {
                        out.append(css[i + 1]); i++
                    } else if (c == quote) {
                        quote = null
                    }
                    i++
                }
                c == '"' || c == '\'' -> {
                    quote = c; out.append(c); i++
                }
                c == '/' && i + 1 < css.length && css[i + 1] == '*' -> {
                    val end = css.indexOf("*/", i + 2)
                    i = if (end < 0) css.length else end + 2
                }
                else -> {
                    out.append(c); i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Returns every style rule in [css], including those nested in a conditional at-rule.
     * `@font-face`, `@keyframes` and any unrecognised at-rule are skipped whole — never
     * emit a shim from inside a block whose semantics weren't modelled (`@keyframes typing
     * { to { left:100% } }` would otherwise surface a "rule" whose selector is `to`).
     *
     * Returns an empty list past [maxRules]: a runaway parse must produce no shim at all
     * rather than a megabyte `<style>` block inside a 2 KB index.html.
     */
    fun scan(css: String, maxRules: Int = 400): List<CssRule> {
        val src = stripComments(css)
        val rules = mutableListOf<CssRule>()
        val atStack = ArrayDeque<String>()
        val prelude = StringBuilder()
        var i = 0
        var quote: Char? = null
        var paren = 0
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                prelude.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    prelude.append(src[i + 1]); i++
                } else if (c == quote) {
                    quote = null
                }
                i++
                continue
            }
            when {
                c == '"' || c == '\'' -> {
                    quote = c; prelude.append(c); i++
                }
                c == '(' -> {
                    paren++; prelude.append(c); i++
                }
                c == ')' -> {
                    if (paren > 0) paren--
                    prelude.append(c); i++
                }
                paren > 0 -> {
                    prelude.append(c); i++
                }
                c == ';' -> {
                    // at-rule without a block (@import, @charset) — discard it.
                    prelude.setLength(0); i++
                }
                c == '}' -> {
                    // closes a conditional group we descended into.
                    atStack.removeLastOrNull()
                    prelude.setLength(0); i++
                }
                c == '{' -> {
                    val head = prelude.toString().trim()
                    prelude.setLength(0)
                    if (head.startsWith("@")) {
                        val keyword = head.takeWhile { !it.isWhitespace() && it != '(' }.lowercase()
                        if (keyword in CONDITIONAL_AT_RULES) {
                            atStack.addLast(head)
                            i++
                        } else {
                            i = skipBlock(src, i)
                        }
                    } else {
                        val end = findDeclarationsEnd(src, i + 1)
                        val body = src.substring(i + 1, end)
                        if (head.isNotEmpty()) {
                            rules += CssRule(atStack.lastOrNull(), head, declarations(body))
                            if (rules.size > maxRules) return emptyList()
                        }
                        i = if (end < src.length) end + 1 else end
                    }
                }
                else -> {
                    prelude.append(c); i++
                }
            }
        }
        return rules
    }

    /** [open] indexes the `{`; returns the index just past its matching `}`. */
    private fun skipBlock(src: String, open: Int): Int {
        var depth = 0
        var i = open
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return src.length
    }

    /** Index of the `}` closing a declaration block that starts at [from]. */
    private fun findDeclarationsEnd(src: String, from: Int): Int {
        var i = from
        var quote: Char? = null
        var paren = 0
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '}' -> if (paren == 0) return i
            }
            i++
        }
        return src.length
    }

    /**
     * Splits a declaration block body into (lowercased property, value) pairs.
     *
     * Splitting on `;` and `:` has to respect parens and strings: `url(data:image/png;base64,...)`
     * carries both characters inside one value.
     */
    fun declarations(body: String): List<Pair<String, String>> =
        splitTopLevel(body, ';').mapNotNull { piece ->
            val colon = indexOfTopLevel(piece, ':')
            if (colon <= 0) return@mapNotNull null
            val prop = piece.substring(0, colon).trim().lowercase()
            var value = piece.substring(colon + 1).trim()
            // CSS allows whitespace between `!` and `important`.
            val bang = value.lastIndexOf('!')
            if (bang >= 0 && value.substring(bang).filterNot { it.isWhitespace() }
                    .equals("!important", ignoreCase = true)
            ) {
                value = value.substring(0, bang).trimEnd()
            }
            if (prop.isEmpty() || value.isEmpty()) null else prop to value
        }

    /** Splits a selector list on top-level commas: `:not(a, b)` and `[title="a,b"]` stay whole. */
    fun splitSelectorList(selector: String): List<String> =
        splitTopLevel(selector, ',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The element name the selector actually targets — its subject.
     * `div.body u` -> `u`; `div.body>h1` -> `h1`; `.foo` -> null; `*` -> `*`.
     */
    fun subjectTag(selector: String): String? {
        val compound = lastCompound(selector) ?: return null
        if (compound == "*") return "*"
        val tag = compound.takeWhile { it.isLetterOrDigit() }.lowercase()
        return tag.ifEmpty { null }
    }

    /** The right-most compound selector, i.e. what the selector applies to. */
    fun lastCompound(selector: String): String? {
        var i = selector.length - 1
        var bracket = 0
        var paren = 0
        while (i >= 0) {
            val c = selector[i]
            when {
                c == ']' -> bracket++
                c == '[' -> if (bracket > 0) bracket--
                c == ')' -> paren++
                c == '(' -> if (paren > 0) paren--
                bracket > 0 || paren > 0 -> Unit
                c.isWhitespace() || c == '>' || c == '+' || c == '~' ->
                    return selector.substring(i + 1).trim().ifEmpty { null }
            }
            i--
        }
        return selector.trim().ifEmpty { null }
    }

    private fun splitTopLevel(input: String, separator: Char): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var quote: Char? = null
        var paren = 0
        var bracket = 0
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (quote != null) {
                buf.append(c)
                if (c == '\\' && i + 1 < input.length) {
                    buf.append(input[i + 1]); i++
                } else if (c == quote) {
                    quote = null
                }
                i++
                continue
            }
            when (c) {
                '"', '\'' -> { quote = c; buf.append(c) }
                '(' -> { paren++; buf.append(c) }
                ')' -> { if (paren > 0) paren--; buf.append(c) }
                '[' -> { bracket++; buf.append(c) }
                ']' -> { if (bracket > 0) bracket--; buf.append(c) }
                separator -> if (paren == 0 && bracket == 0) {
                    out += buf.toString(); buf.setLength(0)
                } else {
                    buf.append(c)
                }
                else -> buf.append(c)
            }
            i++
        }
        if (buf.isNotBlank()) out += buf.toString()
        return out
    }

    private fun indexOfTopLevel(input: String, target: Char): Int {
        var quote: Char? = null
        var paren = 0
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '"', '\'' -> quote = c
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                target -> if (paren == 0) return i
            }
            i++
        }
        return -1
    }
}
