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
                       scripts: List<String>? = null) = JpmModuleInfo().apply {
        this.name = name
        this.dir = dir
        this.main = main
        this.hasTests = tests
        this.scripts = scripts
    }

    private fun project(workspace: Boolean, vararg modules: JpmModuleInfo) = JpmProjectInfo().apply {
        projectDir = "/p"
        name = "p"
        this.workspace = workspace
        this.modules = modules.toList()
    }

    private fun labels(info: JpmProjectInfo) = JpmTasks.forProject(info).flatMap { it.tasks }.map { it.label }


    @Test
    fun aBareProjectOffersOnlyWhatRuns() {
        val info = project(false, module("lib", "/p"))
        assertEquals(listOf("build", "clean", "build --release", "build --bundle", "install", "update", "outdated"), labels(info))
    }

    @Test
    fun testsAndMainAddTheirTasks() {
        val info = project(false, module("app", "/p", main = mapOf("main" to "a.App"), tests = true))
        assertEquals(listOf("build", "test", "start", "dev", "clean", "build --release", "build --bundle", "build --native",
            "install", "update", "outdated"), labels(info))
    }

    @Test
    fun severalEntryPoints() {
        val info = project(false, module("app", "/p", main = mapOf("cli" to "a.Cli", "server" to "a.Server")))
        val all = labels(info)
        assertTrue(all.containsAll(listOf("start cli", "dev cli", "start server", "dev server")))
        assertFalse(all.contains("start"))
    }

    @Test
    fun extensionsBringTheCommandsTheirManifestsDescribe() {
        val info = project(false, module("lib", "/p"))
        info.extensionList = listOf(
            JpmExtensionInfo("publish", "project", "0.3.0", "Publish", listOf(JpmExtensionCommand("publish", "to a Maven repository"))),
            JpmExtensionInfo("format", "global", "0.2.0", null, listOf(JpmExtensionCommand("format", null), JpmExtensionCommand("format rules", "list them"))))
        val groups = JpmTasks.forProject(info).filter { it.name.startsWith("jpm-") }
        assertEquals(listOf("jpm-publish", "jpm-format (global)"), groups.map { it.name })
        assertEquals("to a Maven repository", groups[0].tasks.single().hint)
        assertEquals(listOf("format", "rules"), groups[1].tasks.single { it.label == "format rules" }.args)
        assertTrue(groups.flatMap { it.tasks }.all { it.dir == Path.of("/p") })
    }

    @Test
    fun workspaceModulesGetTheirOwnTasks() {
        val api = module("api", "/p/api")
        val app = module("app", "/p/app", main = mapOf("main" to "a.App"), tests = true, scripts = listOf("gen"))
        val info = project(true, api, app)

        val start = JpmTasks.forProject(info).flatMap { it.tasks }.single { it.label == "start" }
        assertEquals(Path.of("/p/app"), start.dir)
        assertTrue(labels(info).contains("test"))

        assertEquals(listOf("build", "build --bundle"), JpmTasks.forModule(info, api).map { it.label })
        val appTasks = JpmTasks.forModule(info, app)
        assertEquals(listOf("build", "test", "start", "dev", "build --bundle", "build --native", "run gen"), appTasks.map { it.label })
        assertTrue(appTasks.all { it.dir == Path.of("/p/app") })
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
