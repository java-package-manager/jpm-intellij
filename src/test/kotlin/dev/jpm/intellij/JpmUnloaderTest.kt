package dev.jpm.intellij

import com.intellij.execution.RunManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JpmUnloaderTest : BasePlatformTestCase() {
    fun testReleasesWhatWouldKeepThePluginLoaded() {
        val runManager = RunManager.getInstance(project)
        val type = JpmConfigurationType.get()
        val temporary = runManager.createConfiguration("jpm build", type).also { it.isTemporary = true; runManager.addConfiguration(it) }
        val saved = runManager.createConfiguration("jpm test (saved)", type).also { runManager.addConfiguration(it) }
        runManager.selectedConfiguration = temporary

        val ours = NotificationGroupManager.getInstance().getNotificationGroup("jpm")
            .createNotification("Could not load the jpm project", "x", NotificationType.ERROR)
        ours.notify(project)
        assertFalse(ours.isExpired)

        JpmUnloader.release(project)

        assertNull("temporary jpm configurations are removed", runManager.findSettings(temporary.configuration))
        assertNotNull("saved jpm configurations stay", runManager.findSettings(saved.configuration))
        assertNull(runManager.selectedConfiguration)
        assertTrue("jpm notifications are expired", ours.isExpired)
        assertEmpty(NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, project)
            .filter { it.groupId == "jpm" && !it.isExpired })

        JpmSync.get(project).reload()
        assertFalse(JpmSync.get(project).loading)
        runManager.removeConfiguration(saved)
    }
}
