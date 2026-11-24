package dev.jpm.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class JpmFormatOnSaveTest {
    private fun apply(old: String, new: String): String {
        val e = JpmFormatOnSave.edit(old, new)
        return old.substring(0, e.start) + e.replacement + old.substring(e.end)
    }

    @Test
    fun editReplacesOnlyWhatChanged() {
        val old = "class A {\n  int x;\n}\n"
        val new = "class A {\n    int x;\n}\n"
        assertEquals(JpmFormatOnSave.Edit(12, 12, "  "), JpmFormatOnSave.edit(old, new))
        for ((a, b) in listOf(old to new, "aaa" to "aa", "aa" to "aaa", "" to "x", "x" to "", "abc" to "xyz", "abba" to "aba")) {
            assertEquals(b, apply(a, b))
        }
    }

    @Test
    fun theInnermostProjectHoldingTheFileRunsTheFormatter() {
        val roots = listOf(Path.of("/w"), Path.of("/w/core"), Path.of("/other"))
        assertEquals(Path.of("/w/core"), JpmFormatOnSave.rootFor(Path.of("/w/core/src/A.java"), roots))
        assertEquals(Path.of("/w"), JpmFormatOnSave.rootFor(Path.of("/w/cores/A.java"), roots))
        assertNull(JpmFormatOnSave.rootFor(Path.of("/elsewhere/A.java"), roots))
    }

    @Test
    fun onlyJavaFilesAreFormatted() {
        assertTrue(JpmFormatOnSave.isFormatted("A.java"))
        assertFalse(JpmFormatOnSave.isFormatted("project.jsonc"))
        assertFalse(JpmFormatOnSave.isFormatted("build.kts"))
    }
}
