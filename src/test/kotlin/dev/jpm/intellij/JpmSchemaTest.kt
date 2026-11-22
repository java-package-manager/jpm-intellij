package dev.jpm.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection
import java.nio.file.Files
import java.nio.file.Path

/**
 * The schema comes from the installed jpm (schema/manifest); here a small stand-in is served the same
 * way. What the schema says is jpm's business and tested there.
 */
class JpmSchemaTest : BasePlatformTestCase() {
    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("jpm-schema")
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
        val schema = dir.resolve("schemas/1.0.0/project.schema.json")
        Files.createDirectories(schema.parent)
        Files.writeString(schema, SCHEMA)
        JpmSchemaProvider.use(schema)
        JsonSchemaService.Impl.get(project).reset()
        myFixture.enableInspections(JsonSchemaComplianceInspection())
    }

    override fun tearDown() {
        try {
            JpmSchemaProvider.use(null)
            JpmSchemaProvider.forget()
            dir.toFile().deleteRecursively()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testProjectJsoncUsesJpmsSchema() {
        val file = myFixture.configureByText(Jpm.MANIFEST, """{ "name": "demo" }""").virtualFile
        val schemas = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file)
        assertTrue("schema for project.jsonc: $schemas", schemas.any { it.path.endsWith("schemas/1.0.0/project.schema.json") })
    }

    fun testValidManifestHasNoWarnings() {
        myFixture.configureByText(Jpm.MANIFEST, """
            // comments are fine in project.jsonc
            {
              "name": "demo",
              "java": "21",
              "runtime": { "org.slf4j:slf4j-api": "2.0.16" },
            }
        """.trimIndent())
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING).map { it.description })
    }

    fun testMistakesAreFlagged() {
        myFixture.configureByText(Jpm.MANIFEST, """{ "name": "demo", "dependencies": {} }""")
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING).map { it.description.orEmpty() }
        assertTrue(warnings.toString(), warnings.any { "dependencies" in it })
    }

    fun testCompletesFields() {
        myFixture.configureByText(Jpm.MANIFEST, """{ "name": "demo", "<caret>" }""")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "runtime", "java")
    }

    fun testOtherJsonFilesAreLeftAlone() {
        val file = myFixture.configureByText("other.jsonc", """{ "anything": 1 }""").virtualFile
        val schemas = JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file)
        assertFalse(schemas.any { it.name == "project.schema.json" })
    }

    fun testWithoutJpmThereIsNoSchema() {
        JpmSchemaProvider.use(null)
        JsonSchemaService.Impl.get(project).reset()
        val file = myFixture.configureByText(Jpm.MANIFEST, """{ "name": "demo" }""").virtualFile
        assertFalse(JsonSchemaService.Impl.get(project).getSchemaFilesForFile(file).any { it.name == "project.schema.json" })
        assertNull(JpmSchemaProvider.remoteSource)
    }


    companion object {
        private val SCHEMA = """
            {
              "${'$'}schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "additionalProperties": false,
              "required": ["name"],
              "properties": {
                "${'$'}schema": { "type": "string" },
                "name": { "type": "string" },
                "java": { "type": "string" },
                "runtime": { "type": "object", "additionalProperties": { "type": "string" } }
              }
            }
        """.trimIndent()
    }
}
