package dev.jpm.intellij

import org.junit.Assert.assertEquals
import org.junit.Test

class JpmRunConfigsTest {
    private fun module(name: String, dir: String, main: Map<String, String>? = null, scripts: List<String>? = null) = JpmModuleInfo().apply {
        this.name = name
        this.dir = dir
        this.main = main
        this.scripts = scripts
    }

    @Test
    fun aProjectGetsOneForEachEntryPointAndScript() {
        val info = JpmProjectInfo().apply {
            projectDir = "/p"
            name = "p"
            modules = listOf(module("app", "/p", mapOf("main" to "app.Main", "server" to "app.Server"), listOf("lint")))
        }
        assertEquals(listOf(
            JpmRunConfigs.Spec("jpm start", listOf("start"), "/p"),
            JpmRunConfigs.Spec("jpm start server", listOf("start", "server"), "/p"),
            JpmRunConfigs.Spec("jpm run lint", listOf("run", "lint"), "/p"),
        ), JpmRunConfigs.wanted(listOf(info)))
    }

    @Test
    fun workspaceModulesAreNamedAndRootScriptsRunAtTheRoot() {
        val info = JpmProjectInfo().apply {
            projectDir = "/ws"
            name = "shop"
            workspace = true
            scripts = listOf("hello", "lint")
            modules = listOf(module("app", "/ws/app", mapOf("main" to "shop.Main")), module("core", "/ws/core", scripts = listOf("lint")))
        }
        assertEquals(listOf("app: jpm start", "core: jpm run lint", "shop: jpm run hello"), JpmRunConfigs.wanted(listOf(info)).map { it.name })
        assertEquals("/ws", JpmRunConfigs.wanted(listOf(info)).last().dir)
    }
}
