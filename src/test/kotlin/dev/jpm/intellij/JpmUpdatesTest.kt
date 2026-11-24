package dev.jpm.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JpmUpdatesTest {
    @Test
    fun readsTheNewerJpmFromUpgradeCheck() {
        assertEquals(JpmUpdates.JpmUpdate("0.15.0", "0.14.0"),
            JpmUpdates.parseUpgradeCheck("jpm 0.15.0 is available (you have 0.14.0). Run `jpm upgrade`.\n"))
        assertEquals(JpmUpdates.JpmUpdate("0.16.0-beta.1", "0.15.0"),
            JpmUpdates.parseUpgradeCheck("jpm 0.16.0-beta.1 is the newest prerelease or release (you have 0.15.0). Run `jpm upgrade --canary`."))
    }

    @Test
    fun anUpToDateJpmHasNoUpdate() {
        assertNull(JpmUpdates.parseUpgradeCheck("jpm 0.15.0 is up to date."))
        assertNull(JpmUpdates.parseUpgradeCheck("jpm 0.15.0 is the newest prerelease or release."))
        assertNull(JpmUpdates.parseUpgradeCheck("jpm 0.16.0-beta.1 is up to date (newer than the latest release, 0.15.0)."))
    }

    @Test
    fun readsTheOutdatedGlobalExtensions() {
        val out = """
                Fetching https://github.com/java-package-manager/jpm-format
                ESC[1;32m    OutdatedESC[0m jpm-format 0.5.0 (abc1234) -> 0.6.0
              Up to date jpm-publish 0.3.1
              newer outside ^0.3.0: 1.0.0. Run `jpm update -g --latest publish` to move to it.
                Outdated jpm-lint 1.0.0 -> 1.2.0
            Run `jpm update -g` to install the updates.
        """.trimIndent().replace("ESC", "\u001B")
        assertEquals(listOf(JpmUpdates.ExtensionUpdate("format", "0.5.0 (abc1234)", "0.6.0"),
            JpmUpdates.ExtensionUpdate("lint", "1.0.0", "1.2.0")), JpmUpdates.parseExtensionCheck(out))
        assertEquals(emptyList<JpmUpdates.ExtensionUpdate>(),
            JpmUpdates.parseExtensionCheck("No global extensions are installed. Install one with `jpm add -g <dependency>`."))
    }

    @Test
    fun anOlderJpmHasNoExtensionCheck() {
        assertTrue(JpmUpdates.lacksExtensionCheck("error: Unknown option --check\n"))
        assertFalse(JpmUpdates.lacksExtensionCheck("    Outdated jpm-format 0.5.0 -> 0.6.0"))
    }

    @Test
    fun theChannelPicksTheUpgrade() {
        assertEquals(listOf("upgrade", "--check", "--color=never"), JpmUpdates.upgradeArgs(canary = false, check = true))
        assertEquals(listOf("upgrade", "--canary", "--color=never"), JpmUpdates.upgradeArgs(canary = true, check = false))
    }
}
