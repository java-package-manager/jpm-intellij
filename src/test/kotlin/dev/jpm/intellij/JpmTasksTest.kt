package dev.jpm.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class JpmTasksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun module(name: String, dir: String, main: Map<String, String>? = null, tests: Boolean = false,
                       extensions: Map<String, Any?>? = null, scripts: List<String>? = null) = JpmModuleInfo().apply {
        this.name = name
        this.dir = dir
        this.main = main
        this.hasTests = tests
        this.extensions = extensions
        this.scripts = scripts
    }

    private fun project(workspace: Boolean, vararg modules: JpmModuleInfo, extensions: Map<String, Any?>? = null) = JpmProjectInfo().apply {
        projectDir = "/p"
        name = "p"
        this.workspace = workspace
        this.extensions = extensions
        this.modules = modules.toList()
    }

    private fun labels(info: JpmProjectInfo) = JpmTasks.forProject(info).flatMap { it.tasks }.map { it.label }

    private fun repos(vararg names: String): Map<String, Any?> = mapOf("publish" to mapOf("repositories" to names.associateWith { mapOf<String, Any>() }))

    @Test
    fun aBareProjectOffersOnlyWhatRuns() {
        val info = project(false, module("lib", "/p"))
        assertEquals(listOf("build", "clean", "build --release", "build --bundle", "install", "update", "outdated", "publish --local"), labels(info))
    }

    @Test
    fun testsMainAndPublishingAddTheirTasks() {
        val info = project(false, module("app", "/p", main = mapOf("main" to "a.App"), tests = true, extensions = repos("central")))
        val all = labels(info)
        assertEquals(listOf("build", "test", "start", "dev", "clean", "build --release", "build --bundle", "build --native",
            "install", "update", "outdated", "publish", "publish --local"), all)
        val publish = JpmTasks.forProject(info).flatMap { it.tasks }.single { it.label == "publish" }
        assertEquals("to central", publish.hint)
    }

    @Test
    fun severalEntryPointsAndSeveralRepositories() {
        val info = project(false, module("app", "/p", main = mapOf("cli" to "a.Cli", "server" to "a.Server")), extensions = repos("releases", "central"))
        val all = labels(info)
        assertTrue(all.containsAll(listOf("start cli", "dev cli", "start server", "dev server", "publish releases", "publish central")))
        assertFalse(all.contains("start") || all.contains("publish"))
    }

    @Test
    fun workspaceModulesGetTheirOwnTasks() {
        val api = module("api", "/p/api", extensions = mapOf("publish" to mapOf("skip" to true)))
        val app = module("app", "/p/app", main = mapOf("main" to "a.App"), tests = true, scripts = listOf("gen"))
        val info = project(true, api, app, extensions = repos("central"))

        val start = JpmTasks.forProject(info).flatMap { it.tasks }.single { it.label == "start" }
        assertEquals(Path.of("/p/app"), start.dir)
        assertTrue(labels(info).contains("test"))

        assertEquals(listOf("build", "build --bundle"), JpmTasks.forModule(info, api).map { it.label })
        val appTasks = JpmTasks.forModule(info, app)
        assertEquals(listOf("build", "test", "start", "dev", "build --bundle", "build --native", "publish", "publish --local", "run gen"),
            appTasks.map { it.label })
        assertTrue(appTasks.filter { !it.label.startsWith("publish") }.all { it.dir == Path.of("/p/app") })
        assertEquals(listOf("publish", "--module", "app"), appTasks.single { it.label == "publish" }.args)
    }

    @Test
    fun noStartAtTheRootWhenSeveralModulesHaveAMain() {
        val info = project(true, module("a", "/p/a", main = mapOf("main" to "a.A")), module("b", "/p/b", main = mapOf("main" to "b.B")))
        val all = labels(info)
        assertFalse(all.contains("start") || all.contains("dev"))
        assertTrue(all.contains("build --bundle"))
    }

    @Test
    fun findsTestSourcesButNotTestAssets() {
        val dir = tmp.root.toPath()
        val m = JpmModuleInfo().apply { testDir = dir.resolve("test").toString(); testResourceDir = dir.resolve("test/assets").toString() }
        assertFalse(JpmSync.hasTestSources(m))
        Files.createDirectories(dir.resolve("test/assets/x"))
        Files.writeString(dir.resolve("test/assets/x/Template.java"), "not a test")
        assertFalse(JpmSync.hasTestSources(m))
        Files.createDirectories(dir.resolve("test/demo"))
        Files.writeString(dir.resolve("test/demo/AppTest.java"), "class AppTest {}")
        assertTrue(JpmSync.hasTestSources(m))
    }
}
