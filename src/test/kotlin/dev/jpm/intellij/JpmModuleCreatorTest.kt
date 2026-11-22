package dev.jpm.intellij

import com.intellij.ide.projectWizard.generators.BuildSystemJavaNewProjectWizard
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class JpmModuleCreatorTest : HeavyPlatformTestCase() {
    private lateinit var base: Path

    override fun setUp() {
        super.setUp()
        base = Path.of(project.basePath!!)
        Files.createDirectories(base)
    }

    private fun write(rel: String, text: String) = base.resolve(rel).also { Files.createDirectories(it.parent) }.writeText(text.trimIndent())

    private fun add(dir: Path) = run {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)?.refresh(false, true)
        var r: JpmModuleCreator.Result? = null
        ApplicationManager.getApplication().invokeAndWait { r = JpmModuleCreator.addToEnclosingWorkspace(project, dir) }
        r!!
    }

    fun testIsABuildSystemOfTheJavaWizard() {
        val names = BuildSystemJavaNewProjectWizard.EP_NAME.extensionList.map { it.name }
        assertContainsElements(names, "jpm")
    }

    fun testNewModuleJoinsTheWorkspaceKeepingCommentsAndLayout() {
        write("project.jsonc", """
            // the workspace
            {
              "name": "ws",
              "version": "1.0.0",
              "workspace": [
                "core" // the library
              ]
            }
        """)
        Files.createDirectories(base.resolve("tools/app"))
        assertEquals(JpmModuleCreator.Result.WORKSPACE_MODULE, add(base.resolve("tools/app")))
        val text = base.resolve("project.jsonc").readText()
        assertTrue(text, text.contains("\"core\", \"tools/app\" // the library"))
        assertTrue(text.startsWith("// the workspace"))
        assertEquals(JpmModuleCreator.Result.WORKSPACE_MODULE, add(base.resolve("tools/app")))
        assertEquals(text, base.resolve("project.jsonc").readText())
    }

    fun testEmptyWorkspaceList() {
        write("project.jsonc", """{ "name": "ws", "workspace": [] }""")
        assertEquals(JpmModuleCreator.Result.WORKSPACE_MODULE, add(base.resolve("first")))
        assertTrue(base.resolve("project.jsonc").readText().contains("""["first"]"""))
    }

    fun testOutsideAWorkspaceTheModuleIsItsOwnProject() {
        write("project.jsonc", """{ "name": "single", "version": "1.0.0" }""")
        assertEquals(JpmModuleCreator.Result.NESTED_PROJECT, add(base.resolve("other")))
        assertFalse(base.resolve("project.jsonc").readText().contains("other"))
    }

    fun testCreatesAndRegistersARealModule() {
        System.getenv("JPM_TEST_EXE")?.let { JpmSettings.get().state.jpmPath = it }
        if (Jpm.executable() == null) {
            println("Skipped: no jpm (install it, or set JPM_TEST_EXE)")
            return
        }
        write("project.jsonc", """{ "name": "ws", "version": "1.0.0", "workspace": [] }""")
        val dir = base.resolve("app")
        var result: JpmModuleCreator.Result? = null
        ApplicationManager.getApplication().invokeAndWait {
            result = JpmModuleCreator.create(project, dir, "app", addToWorkspace = true)
        }
        assertEquals(JpmModuleCreator.Result.WORKSPACE_MODULE, result)
        assertTrue(Files.isRegularFile(dir.resolve("project.jsonc")))
        assertTrue(Files.isRegularFile(dir.resolve("src/app/App.java")))
        assertTrue(base.resolve("project.jsonc").readText().contains("""["app"]"""))
        assertEquals(listOf("app"), JpmApi.load(base).modules.map { it.name })
    }
}
