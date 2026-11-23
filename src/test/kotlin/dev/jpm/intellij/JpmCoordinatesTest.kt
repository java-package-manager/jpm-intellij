package dev.jpm.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JpmCoordinatesTest : BasePlatformTestCase() {
    private val asked = ArrayList<String>()

    override fun setUp() {
        super.setUp()
        JpmCoordinates.clear()
        JpmCoordinates.search = { _, q ->
            asked.add(q)
            listOf(JpmCoordinates.Hit("org.example:money", "2.0.0"), JpmCoordinates.Hit("org.example:money-extra", null), JpmCoordinates.Hit("org.other:x", null))
        }
    }

    override fun tearDown() {
        try {
            JpmCoordinates.search = { _, _ -> emptyList() }
            JpmCoordinates.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testADependencyKeyCompletesWithMatchingCoordinates() {
        myFixture.configureByText(Jpm.MANIFEST, "{ \"runtime\": { \"org.example:mo<caret>\": \"1.0\" } }")
        myFixture.completeBasic()
        assertEquals(listOf("org.example:money", "org.example:money-extra"), myFixture.lookupElementStrings.orEmpty().filter { it.contains(':') })
        assertEquals(listOf("org.example:mo"), asked)
    }

    fun testEachQueryIsAskedOnce() {
        myFixture.configureByText(Jpm.MANIFEST, "{ \"dev\": { \"org.example:mo<caret>\": \"1.0\" } }")
        myFixture.completeBasic()
        myFixture.completeBasic()
        assertEquals(listOf("org.example:mo"), asked)
    }

    fun testOtherKeysAndWorkspaceModulesAreNotCompleted() {
        myFixture.configureByText(Jpm.MANIFEST, "{ \"scripts\": { \"org.example:mo<caret>\": \"x\" } }")
        myFixture.completeBasic()
        myFixture.configureByText(Jpm.MANIFEST, "{ \"runtime\": { \"workspace:co<caret>\": \"*\" } }")
        myFixture.completeBasic()
        assertEquals(emptyList<String>(), asked)
    }
}
