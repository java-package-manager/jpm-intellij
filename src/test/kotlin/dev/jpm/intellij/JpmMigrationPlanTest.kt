package dev.jpm.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class JpmMigrationPlanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(root: Path, rel: String, text: String = "x") {
        val f = root.resolve(rel)
        Files.createDirectories(f.parent)
        Files.writeString(f, text)
    }

    @Test
    fun theDryRunSplitsIntoManifestsAndWarnings() {
        val out = "// project.jsonc\n{\n  \"name\": \"shop\",\n  \"workspace\": [\"core\"]\n}\n// core/project.jsonc\n{ \"name\": \"core\" }\n"
        val err = "Picked up JAVA_TOOL_OPTIONS: x\nwarning: the plugin maven-shade-plugin has no jpm equivalent\n   Imported nothing\n"
        val p = JpmMigrationPlan.parse(out, err)
        assertEquals(listOf("", "core"), p.manifests.keys.toList())
        assertEquals("{ \"name\": \"core\" }\n", p.manifests["core"])
        assertEquals(listOf("the plugin maven-shade-plugin has no jpm equivalent"), p.warnings)
    }

    @Test
    fun theLayoutEntryGoes() {
        val text = "{\n  \"name\": \"a\",\n  \"layout\": \"maven\",\n  \"java\": \"21\"\n}\n"
        assertTrue(JpmMigrationPlan.usesMavenLayout(text))
        assertEquals("{\n  \"name\": \"a\",\n  \"java\": \"21\"\n}\n", JpmMigrationPlan.withoutLayout(text))
        assertEquals("{ \"name\": \"a\" }", JpmMigrationPlan.withoutLayout("{ \"name\": \"a\", \"layout\": \"maven\" }"))
        assertFalse(JpmMigrationPlan.usesMavenLayout("{ \"name\": \"a\" }"))
    }

    @Test
    fun mavenFoldersMoveIntoJpmsLayout() {
        val m = tmp.newFolder("m").toPath()
        write(m, "src/main/java/app/App.java")
        write(m, "src/main/kotlin/app/Util.kt")
        write(m, "src/main/resources/app.properties")
        write(m, "src/test/java/app/AppTest.java")
        write(m, "src/test/resources/fixture.txt")
        JpmMigrationPlan.switchLayout(m)
        for (f in listOf("src/app/App.java", "src/app/Util.kt", "src/assets/app.properties", "test/app/AppTest.java", "test/assets/fixture.txt")) {
            assertTrue(f, Files.isRegularFile(m.resolve(f)))
        }
        assertFalse(Files.exists(m.resolve("src/main")))
        assertFalse(Files.exists(m.resolve("src/test")))
    }

    @Test
    fun aFolderWithNoPlaceStopsTheSwitchBeforeAnythingMoves() {
        val m = tmp.newFolder("m").toPath()
        write(m, "src/main/java/app/App.java")
        write(m, "src/main/webapp/index.html")
        val e = assertThrows(IllegalStateException::class.java) { JpmMigrationPlan.switchLayout(m) }
        assertTrue(e.message!!, e.message!!.contains("webapp"))
        assertTrue(Files.isRegularFile(m.resolve("src/main/java/app/App.java")))
    }

    @Test
    fun theOldBuildIsItsFilesWrapperAndOutput() {
        val r = tmp.newFolder("r").toPath()
        for (f in listOf("build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.properties",
                "gradle.properties", "build/libs/a.jar", "core/build.gradle.kts", "core/build/x", "docs/build/keep")) write(r, f)
        val old = JpmMigrationPlan.oldBuild(r, listOf(r.resolve("core"), r.resolve("docs"))).map { r.relativize(it).toString().replace('\\', '/') }.toSet()
        assertEquals(setOf("build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat", "gradle", "gradle.properties", "build",
            "core/build.gradle.kts", "core/build"), old)
        JpmMigrationPlan.delete(JpmMigrationPlan.oldBuild(r, listOf(r.resolve("core"))))
        assertFalse(Files.exists(r.resolve("gradle")))
        assertTrue(Files.exists(r.resolve("docs/build/keep")))
    }

    @Test
    fun leftoversAreFoundWhereverGradleOrMavenIsStillUsed() {
        val r = tmp.newFolder("r").toPath()
        write(r, ".github/workflows/ci.yml", "jobs:\n  b:\n    steps:\n      - uses: gradle/actions/setup-gradle@v4\n      - run: ./gradlew build\n")
        write(r, "Dockerfile", "FROM eclipse-temurin:21\nRUN mvn -q package\n")
        write(r, "scripts/release.sh", "#!/bin/sh\necho hi\n")
        write(r, ".github/dependabot.yml", "updates:\n  - package-ecosystem: gradle\n")
        write(r, ".gitignore", "build/\n.idea/\n")
        write(r, ".idea/runConfigurations/app.xml", "<component><configuration type=\"GradleRunConfiguration\"/></component>")
        val found = JpmMigrationPlan.leftovers(r).map { r.relativize(it.file).toString().replace('\\', '/') + ":" + it.line }
        assertEquals(listOf(".github/dependabot.yml:2", ".github/workflows/ci.yml:4", ".github/workflows/ci.yml:5", ".gitignore:1", "Dockerfile:2",
            ".idea/runConfigurations/app.xml:0"), found)
    }
}
