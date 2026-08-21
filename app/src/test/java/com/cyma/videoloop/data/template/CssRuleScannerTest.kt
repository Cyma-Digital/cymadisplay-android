package com.cyma.videoloop.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CssRuleScannerTest {

    @Test
    fun `comments are stripped but a comment opener inside a string survives`() {
        assertEquals("a { }", CssRuleScanner.stripComments("a {/* gone */ }"))
        assertEquals("""a { content: "/*"; }""", CssRuleScanner.stripComments("""a { content: "/*"; }"""))
    }

    @Test
    fun `an unterminated comment runs to end of input`() {
        assertEquals("a { } ", CssRuleScanner.stripComments("a { } /* never closed"))
    }

    @Test
    fun `declarations split around semicolons and colons inside a url`() {
        val decls = CssRuleScanner.declarations("""background: url(data:image/png;base64,AA==); color: red""")
        assertEquals(
            listOf("background" to "url(data:image/png;base64,AA==)", "color" to "red"),
            decls,
        )
    }

    @Test
    fun `important is stripped from the value`() {
        assertEquals(listOf("color" to "#fff"), CssRuleScanner.declarations("color: #fff !important;"))
        assertEquals(listOf("color" to "#fff"), CssRuleScanner.declarations("color: #fff ! important ;"))
    }

    @Test
    fun `selector lists split only on top-level commas`() {
        assertEquals(
            listOf("div.body u", "div.body strike"),
            CssRuleScanner.splitSelectorList("div.body u,\n            div.body strike"),
        )
        assertEquals(listOf("""a[title="x,y"]"""), CssRuleScanner.splitSelectorList("""a[title="x,y"]"""))
        assertEquals(listOf("li:not(.a, .b)"), CssRuleScanner.splitSelectorList("li:not(.a, .b)"))
    }

    @Test
    fun `subject is the right-most element name`() {
        assertEquals("u", CssRuleScanner.subjectTag("div.body u"))
        assertEquals("h1", CssRuleScanner.subjectTag("div.body>h1"))
        assertEquals("h1", CssRuleScanner.subjectTag("div.body + h1"))
        assertEquals("*", CssRuleScanner.subjectTag("*"))
        assertNull("class-only selectors have no element subject", CssRuleScanner.subjectTag("#text-editor .foo"))
    }

    @Test
    fun `values holding commas inside parens stay in one declaration`() {
        val rules = CssRuleScanner.scan(
            ".content { background-image: radial-gradient(#2f2b2a 0.2vh, transparent 0); background-size: 1vh 1vh; }",
        )
        assertEquals(1, rules.size)
        assertEquals("radial-gradient(#2f2b2a 0.2vh, transparent 0)", rules[0].value("background-image"))
        assertEquals("1vh 1vh", rules[0].value("background-size"))
    }

    @Test
    fun `conditional at-rules are descended into and recorded`() {
        val rules = CssRuleScanner.scan("@media screen and (min-width: 10px) { h1 { color: red } } h2 { color: blue }")
        assertEquals(2, rules.size)
        assertEquals("@media screen and (min-width: 10px)", rules[0].atPrelude)
        assertEquals("h1", rules[0].selector)
        assertNull(rules[1].atPrelude)
        assertEquals("h2", rules[1].selector)
    }

    @Test
    fun `keyframes blocks do not surface their steps as rules`() {
        val rules = CssRuleScanner.scan("@keyframes typing { to { left: 100%; width: 0% } } h1 { color: red }")
        assertEquals(listOf("h1"), rules.map { it.selector })
    }

    @Test
    fun `font-face and import are skipped`() {
        val rules = CssRuleScanner.scan(
            """@import url("x.css"); @font-face { font-family: "a"; src: url("a.woff2"), url("b.woff2") } p { color: red }""",
        )
        assertEquals(listOf("p"), rules.map { it.selector })
    }

    @Test
    fun `last declaration of a property wins`() {
        val rules = CssRuleScanner.scan("h1 { text-decoration: underline; text-decoration: none }")
        assertEquals("none", rules[0].value("text-decoration"))
    }

    @Test
    fun `the real template stylesheet parses into the expected rules`() {
        val css = javaClass.classLoader!!.getResourceAsStream("template-7-style.css")!!
            .bufferedReader().use { it.readText() }
        val rules = CssRuleScanner.scan(css)
        val selectors = rules.map { it.selector }

        assertTrue("h1 rule found", selectors.contains("div.body h1"))
        assertTrue("multi-line selector list kept whole", selectors.any { it.startsWith("div.body u,") })
        assertTrue("no keyframe step leaked in", rules.none { it.selector == "to" || it.selector == "0%" })
        assertTrue("no at-rule leaked in", rules.none { it.selector.startsWith("@") })

        val h1 = rules.first { it.selector == "div.body h1" }
        assertEquals("#a5151c", h1.value("text-decoration-color"))
        assertEquals("underline", h1.value("text-decoration"))
        assertEquals("#fff", h1.value("color"))

        val h2 = rules.first { it.selector == "div.body h2" }
        assertEquals("#a5151c", h2.value("text-decoration-color"))
        assertNull("h2 declares no decoration line", h2.value("text-decoration"))
    }

    @Test
    fun `a runaway sheet yields nothing rather than a huge shim`() {
        val css = (1..50).joinToString("") { "h$it { color: red }" }
        assertEquals(emptyList<CssRule>(), CssRuleScanner.scan(css, maxRules = 10))
    }
}
