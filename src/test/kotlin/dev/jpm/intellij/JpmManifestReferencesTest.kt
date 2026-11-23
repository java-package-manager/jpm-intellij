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

    fun testOtherJsonFilesAreLeftAlone() {
        myFixture.addClass("package app; public class Main {}")
        myFixture.configureByText("other.json", "{ \"main\": \"app.Ma<caret>in\" }")
        assertNull(myFixture.file.findReferenceAt(myFixture.caretOffset))
    }
}
