package dev.jpm.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class JpmResolvedVersionHintsTest {
    private val deps = mapOf(("runtime" to "org.example:money") to "1.2.0", ("test" to "org.junit.jupiter:junit-jupiter") to "5.11.4")

    @Test
    fun aRangeShowsWhatItResolvedTo() {
        assertEquals("→ 1.2.0", JpmResolvedVersionHints.hint(listOf("runtime", "org.example:money"), "^1.0.0", deps))
        assertEquals("→ 5.11.4", JpmResolvedVersionHints.hint(listOf("test", "org.junit.jupiter:junit-jupiter", "version"), "*", deps))
    }

    @Test
    fun anExactVersionAndOtherValuesShowNothing() {
        assertNull(JpmResolvedVersionHints.hint(listOf("runtime", "org.example:money"), "1.2.0", deps))
        assertNull(JpmResolvedVersionHints.hint(listOf("main"), "^1.0.0", deps))
        assertNull(JpmResolvedVersionHints.hint(listOf("dev", "org.example:money"), "^1.0.0", deps))
    }

    @Test
    fun theModuleIsTheOneInTheManifestsFolder() {
        val m = JpmModuleInfo().apply {
            dir = "/ws/core"
            dependencies = listOf(JpmDependencyInfo().apply { key = "g:a"; scope = "runtime"; resolved = "2.0" })
        }
        val info = JpmProjectInfo().apply { modules = listOf(m) }
        assertEquals(mapOf(("runtime" to "g:a") to "2.0"), JpmResolvedVersionHints.resolved(listOf(info), Path.of("/ws/core")))
        assertEquals(emptyMap<Pair<String, String>, String>(), JpmResolvedVersionHints.resolved(listOf(info), Path.of("/ws")))
    }
}
