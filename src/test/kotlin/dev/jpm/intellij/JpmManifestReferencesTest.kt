package dev.jpm.intellij

import com.intellij.json.psi.JsonProperty
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPackage
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JpmManifestReferencesTest : LightJavaCodeInsightFixtureTestCase() {
    private fun resolveAt(manifest: String): Any? {
        myFixture.configureByText(Jpm.MANIFEST, manifest)
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) ?: return null
        return if (ref is com.intellij.psi.PsiPolyVariantReference) ref.multiResolve(false).firstOrNull()?.element else ref.resolve()
    }

    fun testMainNamesAClass() {
        myFixture.addClass("package app; public class Main {}")
        assertInstanceOf(resolveAt("{ \"name\": \"a\", \"main\": \"app.Ma<caret>in\" }"), PsiClass::class.java)
    }

    fun testNamedEntryPointsAndTheDefinesClassNameClasses() {
        myFixture.addClass("package app; public class Server {}")
        assertInstanceOf(resolveAt("{ \"main\": { \"server\": \"app.Ser<caret>ver\" } }"), PsiClass::class.java)
        myFixture.addClass("package app; public class Config {}")
        assertInstanceOf(resolveAt("{ \"defineClass\": \"app.Con<caret>fig\" }"), PsiClass::class.java)
    }

    fun testRenamingTheClassUpdatesMain() {
        val cls = myFixture.addClass("package app; public class Main {}")
        myFixture.configureByText(Jpm.MANIFEST, "{ \"name\": \"a\", \"main\": \"app.Main\" }")
        myFixture.renameElement(cls, "Launcher")
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("\"main\": \"app.Launcher\""))
    }

    fun testFoldersAndFilesResolve() {
        myFixture.addFileToProject("core/project.jsonc", "{}")
        assertInstanceOf(resolveAt("{ \"workspace\": [\"co<caret>re\"] }"), PsiDirectory::class.java)
        myFixture.addFileToProject("scripts/Gen.java", "class Gen {}")
        assertInstanceOf(resolveAt("{ \"scripts\": { \"gen\": { \"run\": \"scripts/Ge<caret>n.java\" } } }"), PsiFile::class.java)
        myFixture.addFileToProject("patches/fix.patch", "")
        assertInstanceOf(resolveAt("{ \"patches\": { \"g:a\": \"patches/fi<caret>x.patch\" } }"), PsiFile::class.java)
    }

    fun testAHookNamesItsScript() {
        val target = resolveAt("{ \"scripts\": { \"lint\": \"echo\" }, \"hooks\": { \"prebuild\": \"li<caret>nt\" } }")
        assertInstanceOf(target, JsonProperty::class.java)
        assertEquals("lint", (target as JsonProperty).name)
    }

    fun testRelocatedPackagesAndTheLicenseFile() {
        myFixture.addClass("package org.yaml.snakeyaml; public class Yaml {}")
        assertInstanceOf(resolveAt("{ \"relocate\": { \"org.yaml.snake<caret>yaml\": \"shaded.yaml\" } }"), PsiPackage::class.java)
        myFixture.addFileToProject("LICENSE", "MIT")
        val license = resolveAt("{ \"license\": \"M<caret>IT\" }")
        assertInstanceOf(license, PsiFile::class.java)
        assertEquals("LICENSE", (license as PsiFile).name)
    }

    private fun addLibrary(name: String, root: com.intellij.openapi.vfs.VirtualFile, sources: com.intellij.openapi.vfs.VirtualFile? = null) {
        com.intellij.testFramework.PsiTestUtil.addProjectLibrary(module, name, listOf(root), listOfNotNull(sources))
    }

    fun testADependencyAndAnExclusionNameTheirLibrary() {
        val jar = myFixture.tempDirFixture.findOrCreateDir("libs/money")
        addLibrary("jpm: org.example:money:1.2.0", jar)
        val dep = resolveAt("{ \"runtime\": { \"org.example:mo<caret>ney\": \"^1.0\" } }")
        assertInstanceOf(dep, PsiDirectory::class.java)
        assertEquals(jar, (dep as PsiDirectory).virtualFile)
        assertEquals(jar, (resolveAt("{ \"exclude\": [\"org.example:mo<caret>ney\"] }") as PsiDirectory).virtualFile)
        assertEquals(jar, (resolveAt("{ \"test\": { \"a:b\": { \"version\": \"1\", \"exclude\": [\"org.example:mo<caret>ney\"] } } }") as PsiDirectory).virtualFile)
        assertFalse(resolveAt("{ \"runtime\": { \"org.example:mon<caret>\": \"^1.0\" } }") is PsiDirectory)
    }

    fun testAFilterNamesTheResourcesItMatches() {
        myFixture.addFileToProject("src/assets/app.properties", "v=1")
        myFixture.addFileToProject("src/assets/other.properties", "v=2")
        myFixture.addFileToProject("src/assets/app.yml", "v: 3")
        myFixture.configureByText(Jpm.MANIFEST, "{ \"filter\": [\"*.prop<caret>erties\"] }")
        val ref = myFixture.file.findReferenceAt(myFixture.caretOffset) as com.intellij.psi.PsiPolyVariantReference
        assertEquals(listOf("app.properties", "other.properties"), ref.multiResolve(false).map { (it.element as PsiFile).name }.sorted())
    }

    fun testJavaNamesTheModulesJdk() {
        val target = resolveAt("{ \"java\": \"2<caret>1\" }")
        val home = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).sdk?.homeDirectory
        if (home == null) assertNull(target) else assertEquals(home, (target as PsiDirectory).virtualFile)
    }

    fun testADependencyDefineNamesWhereTheDependencyDeclaresIt() {
        myFixture.addFileToProject("core/project.jsonc", "{ \"name\": \"core\", \"define\": { \"API_KEY\": { \"type\": \"string\", \"env\": \"API_KEY\" } } }")
        myFixture.addFileToProject("project.jsonc", "{ \"name\": \"root\", \"workspace\": [\"core\", \"app\"] }")
        val app = myFixture.addFileToProject("app/project.jsonc", "{ \"name\": \"app\", \"define\": { \"workspace:core\": { \"API_<caret>KEY\": \"x\" } } }")
        myFixture.configureFromExistingVirtualFile(app.virtualFile)
        val target = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()
        assertInstanceOf(target, JsonProperty::class.java)
        assertEquals("API_KEY", (target as JsonProperty).name)
        assertEquals("core", target.containingFile.virtualFile.parent.name)
    }

    fun testOtherJsonFilesAreLeftAlone() {
        myFixture.addClass("package app; public class Main {}")
        myFixture.configureByText("other.json", "{ \"main\": \"app.Ma<caret>in\" }")
        assertNull(myFixture.file.findReferenceAt(myFixture.caretOffset))
    }
}
