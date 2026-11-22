package dev.jpm.intellij

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
    fun parsesJpmInfo() {
        val json = """
            Picked up JAVA_TOOL_OPTIONS: -Dx=y
            {
              "infoVersion": 1, "jpmVersion": "0.5.0", "projectDir": "/p", "name": "demo", "workspace": false,
              "scripts": ["format"],
              "modules": [{
                "name": "demo", "dir": "/p", "sourceDir": "/p/src", "resourceDir": "/p/src/assets",
                "testDir": "/p/test", "testResourceDir": "/p/test/assets", "javaHome": "/jdks/temurin-21",
                "release": 17, "main": {"main": "demo.App"},
                "ideDependencies": [
                  {"id": "workspace:api", "scope": "compile", "module": "api"},
                  {"id": "org.slf4j:slf4j-api", "scope": "provided", "version": "2.0.16",
                   "jars": ["/r/slf4j-api.jar"], "sources": "/r/slf4j-api-sources.jar"}
                ],
                "someFieldAddedLater": true
              }]
            }
        """.trimIndent()
        val info = JpmProjectInfo.parse(json)
        assertEquals("demo", info.name)
        assertEquals(listOf("format"), info.scripts)
        val m = info.modules.single()
        assertEquals(17, m.release)
        assertEquals("/p/src/assets", m.resourceDir)
        assertEquals("api", m.ideDependencies!![0].module)
        assertEquals("provided", m.ideDependencies!![1].scope)
        assertNull(m.ideDependencies!![1].module)
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
