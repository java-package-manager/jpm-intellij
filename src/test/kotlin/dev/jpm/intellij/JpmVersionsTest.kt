package dev.jpm.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JpmVersionsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        JpmVersions.remember("org.example:money", JpmVersions.Known(listOf("1.0.0", "1.2.0", "2.0.0", "2.1.0-rc1"), "2.0.0"))
    }

    fun testANewerReleaseIsOnlyReportedPastTheCurrentVersion() {
        val k = JpmVersions.get("org.example:money")
        assertEquals("2.0.0", JpmVersions.newer("1.2.0", k))
        assertNull(JpmVersions.newer("2.0.0", k))
        assertNull(JpmVersions.newer("2.1.0-rc1", k))
        assertNull(JpmVersions.newer("^1.0.0", k))
    }

    fun testTheInspectionOffersTheNewRelease() {
        myFixture.enableInspections(JpmNewerVersionInspection())
        myFixture.configureByText(Jpm.MANIFEST, "{ \"runtime\": { \"org.example:money\": <weak_warning descr=\"org.example:money 2.0.0 is available (this resolves to 1.0.0)\">\"1.0.<caret>0\"</weak_warning> } }")
        myFixture.checkHighlighting(false, false, true)
        myFixture.launchAction(myFixture.findSingleIntention("Use version 2.0.0"))
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("\"org.example:money\": \"2.0.0\""))
    }

    fun testVersionsAreCompletedNewestFirst() {
        myFixture.configureByText(Jpm.MANIFEST, "{ \"test\": { \"org.example:money\": { \"version\": \"<caret>\" } } }")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        assertEquals(listOf("2.1.0-rc1", "2.0.0", "1.2.0", "1.0.0"), items.filter { it.first().isDigit() })
        assertTrue(items.toString(), "^2.0.0" in items)
    }
}
