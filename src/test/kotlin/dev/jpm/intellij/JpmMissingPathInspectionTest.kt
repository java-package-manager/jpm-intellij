package dev.jpm.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JpmMissingPathInspectionTest : BasePlatformTestCase() {
    fun testMissingFilesAndFoldersAreWarnedAbout() {
        myFixture.enableInspections(JpmMissingPathInspection())
        myFixture.addFileToProject("core/project.jsonc", "{}")
        myFixture.addFileToProject("patches/fix.patch", "")
        myFixture.configureByText(Jpm.MANIFEST, """
            {
              "workspace": ["core", <warning descr="The folder gone does not exist">"gone"</warning>],
              "patches": { "g:a": "patches/fix.patch", "g:b": { "file": <warning descr="The file patches/missing.patch does not exist">"patches/missing.patch"</warning> } },
              "scripts": { "gen": <warning descr="The file scripts/Gen.java does not exist">"scripts/Gen.java"</warning>, "hello": "echo hi" }
            }
        """.trimIndent())
        myFixture.checkHighlighting(true, false, false)
    }
}
