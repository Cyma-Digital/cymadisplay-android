package com.cyma.videoloop.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the injection splice — `render()` itself is covered by the on-device check. */
class TemplateRendererInjectionTest {

    @Test
    fun `styles land at the end of head, after the template's own stylesheet link`() {
        val html = """<html><head><link rel="stylesheet" href="a.css" /></head><body>x</body></html>"""
        val out = TemplateRenderer.injectStyles(html, listOf("a { color: red }"))
        assertTrue(out.indexOf("<style>") > out.indexOf("""href="a.css""""))
        assertTrue(out.indexOf("<style>") < out.indexOf("</head>"))
    }

    @Test
    fun `each block becomes its own style element`() {
        val out = TemplateRenderer.injectStyles("<html><head></head><body></body></html>", listOf("a{}", "b{}"))
        assertEquals(2, Regex("<style>").findAll(out).count())
    }

    @Test
    fun `blank blocks are dropped`() {
        val html = "<html><head></head><body></body></html>"
        assertEquals(html, TemplateRenderer.injectStyles(html, listOf("", "   ")))
    }

    @Test
    fun `a dollar sign in generated css survives`() {
        // Regex.replaceFirst(String) would read `$1` as a group reference here.
        val css = """a[href$=".pdf"] { color: red }"""
        val out = TemplateRenderer.injectStyles("<html><head></head><body></body></html>", listOf(css))
        assertTrue(out.contains(css))
    }

    @Test
    fun `templates without a head fall back to just after body, never to index zero`() {
        val out = TemplateRenderer.injectStyles("<!DOCTYPE html><html><body class=\"b\">x</body></html>", listOf("a{}"))
        assertTrue(out.startsWith("<!DOCTYPE html>"))
        assertTrue(out.indexOf("<style>") > out.indexOf("""<body class="b">"""))
    }

    @Test
    fun `templates with neither head nor body fall back to just after html`() {
        val out = TemplateRenderer.injectStyles("<!DOCTYPE html><html>x</html>", listOf("a{}"))
        assertTrue(out.startsWith("<!DOCTYPE html><html>"))
        assertTrue(out.contains("<style>"))
    }

    @Test
    fun `a fragment with no anchor at all is returned untouched`() {
        assertEquals("<div>x</div>", TemplateRenderer.injectStyles("<div>x</div>", listOf("a{}")))
    }
}
