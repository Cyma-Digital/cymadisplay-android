package com.cyma.videoloop.data.template

/**
 * Makes coloured text decorations render on the fleet's legacy WebViews.
 *
 * The floor is **Chromium 52** (`com.android.webview` 52.0.2743.100 on the API 24 boxes).
 * Three decoration features the templates use shipped in Chrome 57 and are therefore
 * dropped at parse time — silently, with the value falling back to initial/inherited:
 *
 *  - `text-decoration-color: C` → the line paints in the text colour instead of C.
 *    Template 7's white headings on a `#a5151c` underline came out white-on-white.
 *  - `text-decoration-line: underline` → **no line at all**.
 *  - `text-decoration: underline <color>` (shorthand carrying a colour) → the whole
 *    declaration is rejected, so again **no line at all**.
 *
 * Measured on the box (`org.chromium.webview_shell`, same engine, sentinel-colour probe
 * page read back by pixel classification), so don't re-litigate:
 *
 *  - `-webkit-text-decoration-color` is dead too — the prefix buys nothing.
 *  - A propagated decoration paints in the *descendant's* glyph colour, not the
 *    decorating element's, and it follows `-webkit-text-fill-color` as well. In every
 *    probe row the line's colour equalled a glyph colour present in that row. So on this
 *    engine **the native decoration can never be a different colour from its text** —
 *    no `color`/`-webkit-text-fill-color` trick recovers it.
 *
 * Hence the only mechanism left: suppress the native decoration and paint the line as a
 * background stripe. A stripe on an *inline* box is drawn per line fragment and hugs the
 * text exactly, which is what the native underline does — measured against the native
 * line on hardware at 3 px thick, within 1 px of the same position, on both fragments of
 * a wrapped two-line heading. A `border-bottom` on the block, by contrast, marks only the
 * bottom of the whole block and sits 6 px lower.
 *
 * Block subjects (`h1`…`p`) have no inline box of their own, so their content is wrapped
 * in a `<span class="cyma-legacy-deco">` and the stripe goes on the wrapper. The wrapper
 * is inert on its own; only the generated rule, scoped to the original selector, styles it.
 *
 * Every emitted rule is **version-invariant**: `text-decoration: none !important`
 * suppresses the native line on modern engines too, so a box whose WebView is later
 * updated under a cached index.html still shows exactly one line, in the right colour.
 * That is what lets this run unconditionally, with no WebView-version gate.
 *
 * Pure: no Android imports, no I/O — unit-tested on the JVM.
 */
internal object LegacyDecorationShim {

    /** Marker class on the generated wrapper. Referenced only by generated CSS. */
    const val WRAPPER_CLASS = "cyma-legacy-deco"

    /** [html] with wrappers inserted where needed, plus the CSS to inject ("" if none). */
    data class Result(val html: String, val css: String)

    private const val LINE_THICKNESS = "0.09em"

    /** Vertical placement of the stripe inside the inline box, per decoration line. */
    private const val UNDERLINE_POS = "90%"
    private const val LINE_THROUGH_POS = "48%"
    private const val OVERLINE_POS = "8%"

    private const val MAX_CANDIDATES = 32

    private val LINE_KEYWORDS = setOf("underline", "line-through", "overline")

    /** Text-level blocks whose content may be wrapped in an inline span. */
    private val WRAPPABLE_BLOCKS = setOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "dt", "dd",
        "figcaption", "blockquote", "caption", "th", "td",
    )

    /** Inline subjects: the stripe goes straight on them, no wrapper needed. */
    private val INLINE_SUBJECTS = setOf(
        "u", "s", "strike", "del", "ins", "a", "span", "b", "i", "em", "strong",
        "font", "mark", "small", "sub", "sup", "label", "abbr", "cite", "q",
    )

    /** Subjects the UA stylesheet decorates without the template saying so. */
    private val UA_DECORATED = mapOf(
        "u" to "underline", "ins" to "underline", "a" to "underline",
        "s" to "line-through", "strike" to "line-through", "del" to "line-through",
    )

    /** Selectors we refuse to touch — a mis-parse here would wreck every template. */
    private val FORBIDDEN_SUBJECTS = setOf("*", "html", "body")

    private val NAMED_COLORS = setOf(
        "black", "white", "red", "green", "blue", "yellow", "orange", "purple", "gray",
        "grey", "silver", "maroon", "olive", "lime", "aqua", "teal", "navy", "fuchsia",
        "cyan", "magenta", "pink", "brown", "gold", "beige", "ivory", "khaki", "salmon",
    )

    private val HEX_COLOR = Regex("""^#[0-9a-fA-F]{3,8}$""")
    private val FUNC_COLOR = Regex("""^(?:rgb|rgba|hsl|hsla)\([^()]*\)$""", RegexOption.IGNORE_CASE)
    private val INLINE_STYLE_BLOCK = Regex(
        """<style\b[^>]*>(.*?)</style>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private data class Candidate(
        val atPrelude: String?,
        val selector: String,
        val subject: String,
        val lines: List<String>,
        val color: String?,
        val wrap: Boolean,
    )

    /**
     * Scans [styleSheets] plus the document's own `<style>` blocks and returns the
     * rewritten HTML and the compat CSS. Never throws on malformed input; a stylesheet it
     * cannot make sense of yields no shim, which leaves today's rendering untouched.
     */
    fun apply(html: String, styleSheets: List<String>): Result {
        val sheets = styleSheets + INLINE_STYLE_BLOCK.findAll(html).map { it.groupValues[1] }
        val rules = sheets.flatMap { CssRuleScanner.scan(it) }
        if (rules.isEmpty()) return Result(html, "")

        val candidates = rules.flatMap { candidatesFor(it) }.distinct()
        if (candidates.isEmpty() || candidates.size > MAX_CANDIDATES) return Result(html, "")

        val wrappedTags = candidates.filter { it.wrap }.map { it.subject }.toSet()
        val outHtml = if (wrappedTags.isEmpty()) html else wrapContent(html, wrappedTags)

        // Killing the native decoration is only safe once a wrapper actually exists to
        // carry the stripe. If nothing got wrapped (all blank, or same-tag nesting), keep
        // today's rendering — a wrongly-coloured line beats no line.
        val effective = candidates.filterNot { it.wrap && !isWrapped(outHtml, it.subject) }
        if (effective.isEmpty()) return Result(html, "")
        return Result(outHtml, buildCss(effective))
    }

    private fun isWrapped(html: String, tag: String): Boolean =
        Regex("""<$tag\b[^>]*>\s*<span class="$WRAPPER_CLASS">""", RegexOption.IGNORE_CASE)
            .containsMatchIn(html)

    private fun candidatesFor(rule: CssRule): List<Candidate> {
        val shorthand = rule.value("text-decoration")
        val longhandLine = rule.value("text-decoration-line")
        val declaredLines = parseLines(longhandLine ?: shorthand)
        val explicitNone = (longhandLine ?: shorthand)?.split(Regex("""\s+"""))
            ?.any { it.equals("none", ignoreCase = true) } == true
        if (explicitNone) return emptyList()

        val color = rule.value("text-decoration-color")
            ?: rule.value("-webkit-text-decoration-color")
            ?: shorthand?.let { colorTokenOf(it) }

        // A rule declaring a colour but no line (template 7's h2, which draws its red rule
        // with border-bottom and must keep its yellow text) is inert even on a modern
        // engine. Touching it would change a colour the author never decorated.
        if (declaredLines.isEmpty() && color != null && rule.value("text-decoration-color") != null) {
            val uaOnly = CssRuleScanner.splitSelectorList(rule.selector)
                .all { UA_DECORATED.containsKey(CssRuleScanner.subjectTag(it)) }
            if (!uaOnly) return emptyList()
        }

        return CssRuleScanner.splitSelectorList(rule.selector).mapNotNull { selector ->
            val subject = CssRuleScanner.subjectTag(selector) ?: return@mapNotNull null
            if (subject in FORBIDDEN_SUBJECTS) return@mapNotNull null
            if (selector.contains("::") || PSEUDO_STATE.containsMatchIn(selector)) return@mapNotNull null
            // We overwrite background-* on the target, so a rule that paints its own
            // background is off limits.
            if (rule.declarations.any { it.first == "background" || it.first == "background-image" }) {
                return@mapNotNull null
            }

            val lines = declaredLines.ifEmpty { listOfNotNull(UA_DECORATED[subject]) }
            if (lines.isEmpty()) return@mapNotNull null

            val literal = color?.takeIf { isColorLiteral(it) }
            val needsNormalisation = longhandLine != null || (shorthand != null && colorTokenOf(shorthand) != null)
            // Only the stripe needs an inline box to paint into; a normalisation just
            // rewrites the line into a form the engine parses, so it fits any subject.
            val wrap = when (subject) {
                in INLINE_SUBJECTS -> false
                in WRAPPABLE_BLOCKS -> true
                else -> if (literal != null) return@mapNotNull null else false
            }

            when {
                literal != null -> Candidate(rule.atPrelude, selector, subject, lines, literal, wrap)
                // No usable colour, but the line itself is expressed in a form Chromium 52
                // drops whole — restore the line in its inherited colour, which is what the
                // author would have got before adding the colour.
                needsNormalisation -> Candidate(rule.atPrelude, selector, subject, lines, null, wrap = false)
                else -> null
            }
        }
    }

    private val PSEUDO_STATE = Regex(""":(?:hover|focus|active|visited|target)\b""", RegexOption.IGNORE_CASE)

    private fun buildCss(candidates: List<Candidate>): String {
        val byPrelude = candidates.groupBy { it.atPrelude }
        val out = StringBuilder()
        for ((prelude, group) in byPrelude) {
            val body = StringBuilder()
            for (c in group) {
                if (c.color == null) {
                    // Normalisation only: express the line the way Chromium 52 understands.
                    body.append("${c.selector} { text-decoration: ${c.lines.joinToString(" ")} !important; }\n")
                    continue
                }
                // The stripe needs the native line suppressed. When it rides on a wrapper
                // that is a second rule; on the element itself it folds into one.
                if (c.wrap) body.append("${c.selector} { text-decoration: none !important; }\n")
                val target = if (c.wrap) "${c.selector} > span.$WRAPPER_CLASS" else c.selector
                body.append("$target {\n")
                if (!c.wrap) body.append("  text-decoration: none !important;\n")
                body.append("  background-image: ${c.lines.joinToString(", ") { "linear-gradient(${c.color}, ${c.color})" }} !important;\n")
                body.append("  background-repeat: ${c.lines.joinToString(", ") { "repeat-x" }} !important;\n")
                body.append("  background-size: ${c.lines.joinToString(", ") { "4px $LINE_THICKNESS" }} !important;\n")
                body.append("  background-position: ${c.lines.joinToString(", ") { "0 ${positionOf(it)}" }} !important;\n")
                body.append("}\n")
            }
            if (body.isBlank()) continue
            if (prelude == null) {
                out.append(body)
            } else {
                out.append("$prelude {\n").append(body).append("}\n")
            }
        }
        return out.toString()
    }

    private fun positionOf(line: String): String = when (line) {
        "line-through" -> LINE_THROUGH_POS
        "overline" -> OVERLINE_POS
        else -> UNDERLINE_POS
    }

    private fun parseLines(value: String?): List<String> {
        if (value == null) return emptyList()
        return splitTokens(value).filter { it.lowercase() in LINE_KEYWORDS }.map { it.lowercase() }.distinct()
    }

    /**
     * The colour token of a `text-decoration` shorthand, or null. Everything that isn't a
     * line, a style or a control keyword is the colour.
     */
    private fun colorTokenOf(shorthand: String): String? {
        val styles = setOf("solid", "double", "dotted", "dashed", "wavy")
        val control = setOf("none", "initial", "inherit", "unset", "revert", "auto", "from-font")
        return splitTokens(shorthand).firstOrNull {
            val t = it.lowercase()
            t !in LINE_KEYWORDS && t !in styles && t !in control && !t.endsWith("px") &&
                !t.endsWith("em") && !t.endsWith("%")
        }
    }

    /** Splits a value on whitespace, keeping `rgb(1, 2, 3)` in one piece. */
    private fun splitTokens(value: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var paren = 0
        for (c in value) {
            when {
                c == '(' -> { paren++; buf.append(c) }
                c == ')' -> { if (paren > 0) paren--; buf.append(c) }
                c.isWhitespace() && paren == 0 -> {
                    if (buf.isNotBlank()) out += buf.toString()
                    buf.setLength(0)
                }
                else -> buf.append(c)
            }
        }
        if (buf.isNotBlank()) out += buf.toString()
        return out
    }

    private fun isColorLiteral(value: String): Boolean {
        val v = value.trim()
        return HEX_COLOR.matches(v) || FUNC_COLOR.matches(v) || v.lowercase() in NAMED_COLORS
    }

    private val TAG_MARKUP = Regex("<[^>]*>")

    /** Markup-only content (`<h1><br></h1>`) has nothing to underline, so it stays bare. */
    private fun hasText(inner: String): Boolean = TAG_MARKUP.replace(inner, "").isNotBlank()

    /**
     * Wraps the inner content of every [tags] element in an inline span, so the stripe has
     * an inline box to paint per line fragment.
     *
     * Skipped when the element nests one of its own kind (`<li>` inside `<li>`: a
     * non-greedy match would close the wrapper in the wrong place and corrupt the
     * document), when it holds no content, or when it is already wrapped.
     */
    fun wrapContent(html: String, tags: Set<String>): String {
        var out = html
        for (tag in tags) {
            val re = Regex("""(<$tag\b[^>]*>)(.*?)(</$tag\s*>)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            out = re.replace(out) { m ->
                val open = m.groupValues[1]
                val inner = m.groupValues[2]
                val close = m.groupValues[3]
                val nested = inner.contains("<$tag", ignoreCase = true)
                if (nested || !hasText(inner) || inner.contains(WRAPPER_CLASS)) m.value
                else "$open<span class=\"$WRAPPER_CLASS\">$inner</span>$close"
            }
        }
        return out
    }
}
