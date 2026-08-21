package com.cyma.videoloop.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the branches an on-device screenshot cannot see. Engine behaviour is verified on
 * hardware (see [LegacyDecorationShim]'s kdoc); parser correctness is verified here.
 *
 * `template-7-style.css` in test resources is the verbatim stylesheet pulled off a box —
 * it already contains a commented-out declaration block, `@font-face` with a two-URL
 * `src`, `@keyframes` + `@-webkit-keyframes`, gradients with commas inside parens, a
 * two-line selector list, the `h2` colour-without-a-line case and the `u`/`strike`
 * UA-decorated case.
 */
class LegacyDecorationShimTest {

    private val realCss: String =
        javaClass.classLoader!!.getResourceAsStream("template-7-style.css")!!
            .bufferedReader().use { it.readText() }

    private val realHtml = """
        <!DOCTYPE html> <html> <head>
        <link rel="stylesheet" type="text/css" href="assets/template-7/style.css" />
        </head> <body class="body"> <div class="body"> <div class="content">
        <h1 style="text-align: center;"><font color="#ffffff">ALMOCO SEGUNDA FEIRA</font></h1>
        <h1 style="text-align: center;"><br></h1>
        <h2 style="text-align: center;">- OFERTA -</h2>
        <p style="text-align: left;"><span style="background-color: rgb(32, 27, 27);">R$ 52,90</span></p>
        </div> </div> </body> </html>
    """.trimIndent()

    // ---------- the production case ----------

    @Test
    fun `real stylesheet shims the h1 underline via a wrapper`() {
        val result = LegacyDecorationShim.apply(realHtml, listOf(realCss))

        assertTrue("kills the native line", result.css.contains("div.body h1 { text-decoration: none !important; }"))
        assertTrue(
            "stripes the wrapper, not the block",
            result.css.contains("div.body h1 > span.${LegacyDecorationShim.WRAPPER_CLASS} {"),
        )
        assertTrue("uses the authored colour", result.css.contains("linear-gradient(#a5151c, #a5151c)"))
        assertTrue("underline sits near the baseline", result.css.contains("background-position: 0 90% !important;"))
        assertTrue(
            "wrapper inserted around the heading's content",
            result.html.contains("""<h1 style="text-align: center;"><span class="${LegacyDecorationShim.WRAPPER_CLASS}"><font color="#ffffff">"""),
        )
    }

    @Test
    fun `h2 declares a decoration colour but no line so it is left alone`() {
        // Its red rule comes from border-bottom and its text must stay #ffc627.
        val result = LegacyDecorationShim.apply(realHtml, listOf(realCss))
        assertFalse(result.css.contains("div.body h2"))
        assertFalse(result.html.contains("""<h2 style="text-align: center;"><span"""))
    }

    @Test
    fun `real stylesheet stripes UA-decorated inline subjects directly`() {
        val result = LegacyDecorationShim.apply(realHtml, listOf(realCss))
        // `div.body u, div.body strike` declares only a colour; the line comes from the UA
        // stylesheet, and both halves of the selector list must be picked up.
        assertTrue(result.css.contains("div.body u {\n  text-decoration: none !important;"))
        assertTrue(result.css.contains("div.body strike {\n  text-decoration: none !important;"))
        assertFalse("inline subjects need no wrapper", result.css.contains("div.body u > span"))
        assertTrue("strike is a line-through, not an underline", result.css.contains("background-position: 0 48% !important;"))
    }

    @Test
    fun `empty heading is not wrapped`() {
        val result = LegacyDecorationShim.apply(realHtml, listOf(realCss))
        assertFalse(result.html.contains("""<span class="${LegacyDecorationShim.WRAPPER_CLASS}"><br></span>"""))
    }

    @Test
    fun `paragraph text is untouched when nothing decorates it`() {
        val result = LegacyDecorationShim.apply(realHtml, listOf(realCss))
        assertFalse(result.css.contains("div.body p"))
    }

    // ---------- guards ----------

    @Test
    fun `non-literal colour is refused`() {
        val css = "h1 { text-decoration: underline; text-decoration-color: var(--brand); }"
        assertEquals("", LegacyDecorationShim.apply("<html><head></head><body><h1>x</h1></body></html>", listOf(css)).css)
    }

    @Test
    fun `currentColor needs no shim`() {
        val css = "h1 { text-decoration: underline; text-decoration-color: currentColor; }"
        assertEquals("", LegacyDecorationShim.apply("<html><head></head><body><h1>x</h1></body></html>", listOf(css)).css)
    }

    @Test
    fun `wildcard and body subjects are refused`() {
        val css = """
            * { text-decoration: underline; text-decoration-color: #a5151c; }
            body { text-decoration: underline; text-decoration-color: #a5151c; }
        """.trimIndent()
        assertEquals("", LegacyDecorationShim.apply("<html><head></head><body><h1>x</h1></body></html>", listOf(css)).css)
    }

    @Test
    fun `rule painting its own background is refused`() {
        val css = "h1 { text-decoration: underline; text-decoration-color: #a5151c; background-image: url(a.png); }"
        assertEquals("", LegacyDecorationShim.apply("<html><head></head><body><h1>x</h1></body></html>", listOf(css)).css)
    }

    @Test
    fun `explicit text-decoration none is refused`() {
        val css = "h1 { text-decoration-color: #a5151c; text-decoration: none; }"
        assertEquals("", LegacyDecorationShim.apply("<html><head></head><body><h1>x</h1></body></html>", listOf(css)).css)
    }

    @Test
    fun `commented-out declarations are ignored`() {
        val css = "/* h1 { text-decoration: underline; text-decoration-color: #a5151c; } */ h2 { color: red; }"
        assertEquals("", LegacyDecorationShim.apply("<html><head></head><body><h1>x</h1></body></html>", listOf(css)).css)
    }

    @Test
    fun `pseudo elements and states are refused`() {
        val css = """
            h1::after { text-decoration: underline; text-decoration-color: #a5151c; }
            a:hover { text-decoration: underline; text-decoration-color: #a5151c; }
        """.trimIndent()
        assertEquals("", LegacyDecorationShim.apply("<html><head></head><body><h1>x</h1></body></html>", listOf(css)).css)
    }

    @Test
    fun `native decoration is left alone when nothing could be wrapped`() {
        // Nested <li> can't be wrapped safely, so the line must not be killed either.
        val css = "li { text-decoration: underline; text-decoration-color: #a5151c; }"
        val html = "<html><head></head><body><ul><li>a<ul><li>b</li></ul></li></ul></body></html>"
        val result = LegacyDecorationShim.apply(html, listOf(css))
        assertEquals("", result.css)
        assertEquals(html, result.html)
    }

    // ---------- the other two dead properties ----------

    @Test
    fun `shorthand carrying a colour is shimmed`() {
        // Chromium 52 rejects the whole declaration, so the template shows no line at all.
        val css = "p { text-decoration: underline #a5151c; }"
        val result = LegacyDecorationShim.apply("<html><head></head><body><p>x</p></body></html>", listOf(css))
        assertTrue(result.css.contains("p { text-decoration: none !important; }"))
        assertTrue(result.css.contains("linear-gradient(#a5151c, #a5151c)"))
        assertTrue(result.html.contains("""<p><span class="${LegacyDecorationShim.WRAPPER_CLASS}">x</span></p>"""))
    }

    @Test
    fun `text-decoration-line without a colour is normalised to the shorthand`() {
        val css = "p { text-decoration-line: line-through; }"
        val result = LegacyDecorationShim.apply("<html><head></head><body><p>x</p></body></html>", listOf(css))
        assertEquals("p { text-decoration: line-through !important; }\n", result.css)
        assertFalse("a normalisation needs no wrapper", result.html.contains(LegacyDecorationShim.WRAPPER_CLASS))
    }

    @Test
    fun `rgb colours and named colours are accepted`() {
        val css = "p { text-decoration: underline; text-decoration-color: rgb(165, 21, 28); }"
        val result = LegacyDecorationShim.apply("<html><head></head><body><p>x</p></body></html>", listOf(css))
        assertTrue(result.css.contains("linear-gradient(rgb(165, 21, 28), rgb(165, 21, 28))"))
    }

    // ---------- at-rules, wrapping, inline styles ----------

    @Test
    fun `rule inside a media query is re-emitted under the same prelude`() {
        val css = "@media (min-width: 100px) { h1 { text-decoration: underline; text-decoration-color: #a5151c; } }"
        val result = LegacyDecorationShim.apply("<html><head></head><body><h1>x</h1></body></html>", listOf(css))
        assertTrue(result.css.startsWith("@media (min-width: 100px) {"))
        assertTrue(result.css.trimEnd().endsWith("}"))
        assertTrue(result.css.contains("h1 { text-decoration: none !important; }"))
        assertTrue(result.css.contains("h1 > span.${LegacyDecorationShim.WRAPPER_CLASS} {"))
    }

    @Test
    fun `keyframes and font-face bodies are skipped`() {
        val css = """
            @keyframes typing { to { text-decoration: underline; text-decoration-color: #a5151c; } }
            @font-face { font-family: "x"; src: url("a.woff2"), url("b.woff2"); }
        """.trimIndent()
        assertEquals("", LegacyDecorationShim.apply("<html><head></head><body><h1>x</h1></body></html>", listOf(css)).css)
    }

    @Test
    fun `the document's own style blocks are scanned too`() {
        val html = """
            <html><head><style>h1 { text-decoration: underline; text-decoration-color: #a5151c; }</style></head>
            <body><h1>x</h1></body></html>
        """.trimIndent()
        val result = LegacyDecorationShim.apply(html, emptyList())
        assertTrue(result.css.contains("h1 { text-decoration: none !important; }"))
    }

    @Test
    fun `wrapping is idempotent`() {
        val once = LegacyDecorationShim.wrapContent("<h1>x</h1>", setOf("h1"))
        assertEquals(once, LegacyDecorationShim.wrapContent(once, setOf("h1")))
    }

    @Test
    fun `wrapping keeps attributes and multi-line content`() {
        val html = "<h1 class=\"a\" style=\"text-align: center;\">line one<br>line two</h1>"
        val wrapped = LegacyDecorationShim.wrapContent(html, setOf("h1"))
        assertEquals(
            "<h1 class=\"a\" style=\"text-align: center;\">" +
                "<span class=\"${LegacyDecorationShim.WRAPPER_CLASS}\">line one<br>line two</span></h1>",
            wrapped,
        )
    }
}
