package dev.jpm.intellij

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/** Loads jpm projects when they open, offers to migrate Maven and Gradle projects, and checks jpm for updates. */
class JpmStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        JpmUpdates.checkOnStartup(project)
        val dir = Jpm.baseDir(project) ?: return
        if (JpmSync.get(project).roots().isNotEmpty()) {
            JpmSync.get(project).reload()
            return
        }
        val build = Jpm.importableBuild(dir) ?: return
        val props = PropertiesComponent.getInstance(project)
        if (props.getBoolean(DONT_ASK)) return
        NotificationGroupManager.getInstance().getNotificationGroup("jpm")
            .createNotification(
                "Move this project to jpm?",
                "jpm can read $build and create project.jsonc from it. Nothing is changed until you confirm.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Migrate to jpm…") { JpmMigration.start(project) })
            .addAction(NotificationAction.createSimpleExpiring("Don't ask again") { props.setValue(DONT_ASK, true) })
            .notify(project)
    }

    companion object {
        private const val DONT_ASK = "dev.jpm.intellij.migration.dontAsk"
    }
}

/** Reloads when a project.jsonc or project.lock of the project changes on disk. */
class JpmFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val base = project.basePath ?: return
        val sync = JpmSync.get(project)
        var relevant = false
        for (e in events) {
            if (!e.path.startsWith(base)) continue
            val name = e.path.substringAfterLast('/')
            val isDir = e.file?.isDirectory == true
            if ((name == Jpm.MANIFEST || isDir) && (e is VFileCreateEvent || e is VFileDeleteEvent || e is VFileMoveEvent ||
                    (e is VFilePropertyChangeEvent && e.propertyName == com.intellij.openapi.vfs.VirtualFile.PROP_NAME))) {
                sync.rootsChanged()
                relevant = true
            }
            if (name == Jpm.MANIFEST || name == Jpm.LOCKFILE) relevant = true
        }
        if (relevant) sync.scheduleReload()
    }
}
