package dev.jpm.intellij

import dev.jpm.api.client.Results
import dev.jpm.api.json.Jsonc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class JpmModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun turnsProtocolResultsIntoTheModel() {
        fun pkg(id: String, extra: String = "") = """{ "id": "$id", "kind": "maven", "version": "1", "jars": ["/r/$id.jar"]$extra }"""
        val libWithSources = pkg("g:lib", ", \"sources\": \"/r/g:lib-sources.jar\"")
        val load = """
            { "dir": "/p", "name": "demo", "version": "1.0.0", "workspace": false, "scripts": [{ "name": "format", "run": "x" }],
              "modules": [{ "name": "demo", "dir": "/p", "layout": "jpm", "main": { "main": "demo.App" },
                "folders": { "sources": ["/p/src"], "resources": ["/p/src/assets"], "tests": ["/p/test"], "testResources": ["/p/test/assets"],
                             "generatedSources": ["/p/.cache/gen"], "exclude": ["/p/.cache", "/p/dist"] },
                "jdk": { "spec": "21", "home": "/jdks/temurin-21", "release": 17 },
                "dependencies": [{ "key": "workspace:api", "kind": "workspace", "scope": "runtime", "spec": "*", "module": "api" }],
                "packages": [$libWithSources],
                "someFieldAddedLater": true }] }
        """
        val classpaths = """
            { "compile": [{ "id": "workspace:api", "kind": "workspace", "module": "api", "jars": [] }, ${pkg("g:lib")}, ${pkg("g:annotations")}],
              "runtime": [{ "id": "workspace:api", "kind": "workspace", "module": "api", "jars": [] }, ${pkg("g:lib")}, ${pkg("g:driver")}],
              "testCompile": [${pkg("g:junit")}], "testRuntime": [${pkg("g:junit")}] }
        """
        @Suppress("UNCHECKED_CAST")
        fun json(s: String) = Jsonc.parse(s) as Map<String, Any?>
        val project = Results.project(json(load))
        val info = JpmApi.info(project, mapOf("demo" to Results.classpaths(json(classpaths))), "0.12.0")
        assertEquals("demo", info.name)
        assertEquals(listOf("format"), info.scripts)
        val m = info.modules.single()
        assertEquals(17, m.release)
        assertEquals("/jdks/temurin-21", m.javaHome)
        assertEquals("/p/src/assets", m.resourceDir)
        assertEquals("/p/test", m.testDir)
        assertEquals("/p/.cache/gen", m.generatedSourceDir)
        assertEquals(mapOf("main" to "demo.App"), m.main)
        val scopes = m.ideDependencies!!.associate { it.id to it.scope }
        assertEquals(mapOf("workspace:api" to "compile", "g:lib" to "compile", "g:annotations" to "provided", "g:driver" to "runtime",
            "g:junit" to "test"), scopes)
        assertEquals("api", m.ideDependencies!!.first { it.id == "workspace:api" }.module)
        assertEquals("/r/g:lib-sources.jar", m.ideDependencies!!.first { it.id == "g:lib" }.sources)
        assertNull(m.ideDependencies!!.first { it.id == "g:driver" }.sources)
    }

    @Test
    fun findsJpmProjectsInNestedFolders() {
        val root = tmp.root.toPath()
        fun project(rel: String) = root.resolve(rel).also { Files.createDirectories(it) }.resolve(Jpm.MANIFEST).also { Files.writeString(it, "{}") }
        project("services/api")
        project("services/api/fixtures/demo")
        project("tools/cli")
        project("node_modules/pkg")
        project(".git/x")
        project("build/y")
        project("a/b/c/d/e/f/g/too-deep")
        fun found() = Jpm.findRoots(root).map { root.relativize(it).toString().ifEmpty { "." } }
        assertEquals(listOf("services/api", "tools/cli", "services/api/fixtures/demo"), found())

        project(".")
        assertEquals(listOf(".", "services/api", "tools/cli", "services/api/fixtures/demo"), found())
    }

    @Test
    fun jpmOutputIsShownAsTextInNotifications() {
        assertEquals("add it: `jpm add -r &lt;url&gt; a:b`<br>or list it", notificationText("add it: `jpm add -r <url> a:b`\nor list it\n"))
    }

    @Test
    fun excludesCacheButKeepsGeneratedSources() {
        val root = tmp.root.toPath()
        val cache = root.resolve(".cache")
        val gen = cache.resolve("build/demo/generated")
        for (d in listOf(gen, cache.resolve("build/demo/classes"), cache.resolve("build/other"), cache.resolve("patch")))
            Files.createDirectories(d)
        val excluded = JpmProjectSetup.excludedPaths(cache, listOf(gen)).map { root.relativize(it).toString() }
        assertEquals(listOf(".cache/build/demo/classes", ".cache/build/other", ".cache/patch"), excluded)
        assertEquals(listOf(root.resolve("dist")), JpmProjectSetup.excludedPaths(root.resolve("dist"), listOf(gen)))
    }
}
