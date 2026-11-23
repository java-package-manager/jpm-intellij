package dev.jpm.intellij

import org.junit.Assert.assertEquals
import org.junit.Test

class JpmDependencyTreeTest {
    private fun render(line: JpmDependencyTree.Line, indent: String = ""): String =
        indent + line.id + " " + line.version + (if (line.repeated) " (*)" else "") + "\n" + line.children.joinToString("") { render(it, "$indent  ") }

    @Test
    fun eachPackageIsExpandedOnceAndMarkedAfterThat() {
        val graph = mapOf(
            "shop" to JpmGraphNode("1.0", listOf("money", "tax")),
            "money" to JpmGraphNode("1.2", listOf("decimal")),
            "tax" to JpmGraphNode("2.0", listOf("money")),
            "decimal" to JpmGraphNode("0.9", emptyList()),
        )
        assertEquals("""
            shop 1.0
              money 1.2
                decimal 0.9
              tax 2.0
                money 1.2 (*)

        """.trimIndent(), render(JpmDependencyTree.below(graph, "shop")))
    }

    @Test
    fun aCycleEnds() {
        val graph = mapOf("a" to JpmGraphNode("1", listOf("b")), "b" to JpmGraphNode("1", listOf("a")))
        assertEquals("a 1\n  b 1\n    a 1 (*)\n", render(JpmDependencyTree.below(graph, "a")))
    }
}
