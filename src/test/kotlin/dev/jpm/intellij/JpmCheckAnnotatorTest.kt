package dev.jpm.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

/** Asks a real jpm (on this machine, or JPM_TEST_EXE) about a manifest; skipped where there is none. */
class JpmCheckAnnotatorTest : BasePlatformTestCase() {
    fun testJpmsProblemsComeBackWithTheirLine() {
        System.getenv("JPM_TEST_EXE")?.let { JpmSettings.get().state.jpmPath = it }
        if (Jpm.executable() == null) {
            println("Skipped: no jpm (install it, or set JPM_TEST_EXE)")
            return
        }
        val dir = Files.createTempDirectory("jpm-check")
        val manifest = dir.resolve(Jpm.MANIFEST)
        Files.writeString(manifest, "{\n  \"name\": \"app\",\n  \"version\": \"1.0.0\",\n  \"runtim\": {}\n}\n")
        val found = JpmCheckAnnotator().doAnnotate(JpmCheckAnnotator.Input(manifest, 1))
        val unknown = found.firstOrNull { it.message.contains("runtim") }
        assertNotNull(found.joinToString { "${it.line}: ${it.message}" }, unknown)
        assertEquals(4, unknown!!.line)
    }
}
