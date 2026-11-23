package dev.jpm.intellij

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Moves a Maven or Gradle project to jpm. It shows what `jpm import --dry-run` would write, warnings first, in editors
 * where the manifests can be changed before they are written; it can also switch the modules to jpm's layout and
 * delete the old build. Afterwards it lists what still uses Gradle or Maven (CI, Docker, scripts, update bots,
 * .gitignore, run configurations).
 */
object JpmMigration {
    fun start(project: Project) {
        val dir = Jpm.baseDir(project) ?: return
        val build = Jpm.importableBuild(dir) ?: return
        object : Task.Modal(project, "Reading $build", true) {
            var preview: JpmMigrationPlan.Preview? = null
            var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                val out = Jpm.run(dir, listOf("import", "--dry-run"), indicator)
                if (out.exitCode != 0) error = (out.stderr + "\n" + out.stdout).trim()
                else preview = JpmMigrationPlan.parse(out.stdout, out.stderr)
            }

            override fun onSuccess() {
                val p = preview
                if (p == null || p.manifests.isEmpty()) {
                    Messages.showErrorDialog(project, error ?: "jpm import wrote nothing", "jpm Could Not Read $build")
                    return
                }
                val dialog = PreviewDialog(project, build, p)
                if (dialog.showAndGet()) migrate(project, dir, dialog.edited(), dialog.switchLayout(), dialog.deleteOldBuild())
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(project, error.message ?: error.toString(), "Migrate to jpm")
            }
        }.queue()
    }

    private fun migrate(project: Project, dir: Path, manifests: Map<String, String>, switchLayout: Boolean, deleteOld: Boolean) {
        object : Task.Backgroundable(project, "Migrating to jpm", true) {
            override fun run(indicator: ProgressIndicator) {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("jpm")
                val imported = Jpm.run(dir, listOf("import", "--no-install"), indicator)
                if (imported.exitCode != 0) {
                    group.createNotification("Migration to jpm failed", notificationText(imported.stderr + "\n" + imported.stdout), NotificationType.ERROR)
                        .notify(project)
                    return
                }
                val notes = ArrayList<String>()
                val rootMaven = JpmMigrationPlan.usesMavenLayout(manifests[""].orEmpty())
                for ((rel, text) in manifests) {
                    val moduleDir = if (rel.isEmpty()) dir else dir.resolve(rel)
                    val maven = JpmMigrationPlan.usesMavenLayout(text) || rootMaven && !text.contains("\"layout\"")
                    var out = text
                    if (switchLayout && maven) {
                        try {
                            JpmMigrationPlan.switchLayout(moduleDir)
                            out = JpmMigrationPlan.withoutLayout(text)
                        } catch (e: Exception) {
                            notes.add("${if (rel.isEmpty()) "the root" else rel} keeps the Maven layout: ${e.message}")
                        }
                    }
                    Files.writeString(moduleDir.resolve(Jpm.MANIFEST), out)
                }
                if (deleteOld) {
                    JpmMigrationPlan.delete(JpmMigrationPlan.oldBuild(dir, manifests.keys.filter { it.isNotEmpty() }.map { dir.resolve(it) }))
                }
                val installed = Jpm.run(dir, listOf("install"), indicator)
                LocalFileSystem.getInstance().refreshNioFiles(listOf(dir), true, true, null)
                if (installed.exitCode != 0) {
                    group.createNotification("project.jsonc was written, but jpm install failed",
                        notificationText(installed.stderr + "\n" + installed.stdout), NotificationType.WARNING).notify(project)
                }
                val leftovers = JpmMigrationPlan.leftovers(dir)
                val text = buildString {
                    append(if (deleteOld) "project.jsonc was created and the old build removed." else
                        "project.jsonc was created. The Maven or Gradle files are unchanged; remove them once jpm builds the project.")
                    for (n in notes) append("<br>").append(com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(n))
                    if (leftovers.isNotEmpty()) append("<br>${leftovers.size} places still use Gradle or Maven.")
                }
                val n = group.createNotification("Migrated to jpm", text, NotificationType.INFORMATION)
                if (leftovers.isNotEmpty()) n.addAction(NotificationAction.createSimple("Show what still uses Gradle or Maven") {
                    LeftoversDialog(project, dir, leftovers).show()
                })
                n.notify(project)
                ApplicationManager.getApplication().invokeLater({ JpmSync.get(project).reload() }, project.disposed)
            }
        }.queue()
    }

    private class PreviewDialog(private val project: Project, private val build: String, private val preview: JpmMigrationPlan.Preview) :
        DialogWrapper(project) {
        private val editors = LinkedHashMap<String, EditorTextField>()
        private val layout = JBCheckBox("Move the sources to jpm's layout (src/, src/assets/, test/) and drop \"layout\": \"maven\"")
        private val delete = JBCheckBox("Delete the $build build: its build files, wrapper and output folders")

        init {
            title = "Migrate to jpm"
            setOKButtonText("Migrate")
            layout.isEnabled = preview.manifests.values.any(JpmMigrationPlan::usesMavenLayout)
            init()
        }

        fun edited(): Map<String, String> = editors.mapValues { it.value.text }
        fun switchLayout() = layout.isEnabled && layout.isSelected
        fun deleteOldBuild() = delete.isSelected

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
            val top = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
            top.add(JBLabel("<html>jpm read <b>$build</b>. Check the manifests below and change them if needed; they are written as shown.</html>"))
            if (preview.warnings.isNotEmpty()) {
                val warnings = JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.ORANGE, 1), JBUI.Borders.empty(6))
                    add(JBLabel("<html><b>${preview.warnings.size} things do not carry over</b>; review them before migrating:</html>"), BorderLayout.NORTH)
                    add(JBScrollPane(JBList(preview.warnings)).apply { preferredSize = Dimension(JBUI.scale(720), JBUI.scale(110)) }, BorderLayout.CENTER)
                }
                top.add(warnings)
            }
            panel.add(top, BorderLayout.NORTH)
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(Jpm.MANIFEST)
            val tabs = JBTabbedPane()
            for ((rel, text) in preview.manifests) {
                val doc = EditorFactory.getInstance().createDocument(text)
                val editor = EditorTextField(doc, project, fileType, false, false).apply {
                    addSettingsProvider { it.settings.isLineNumbersShown = true }
                    preferredSize = Dimension(JBUI.scale(720), JBUI.scale(380))
                }
                editors[rel] = editor
                tabs.addTab(if (rel.isEmpty()) Jpm.MANIFEST else "$rel/${Jpm.MANIFEST}", editor)
            }
            panel.add(tabs, BorderLayout.CENTER)
            val options = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(this@PreviewDialog.layout)
                add(delete)
            }
            panel.add(options, BorderLayout.SOUTH)
            return panel
        }
    }

    private class LeftoversDialog(private val project: Project, private val dir: Path, private val leftovers: List<JpmMigrationPlan.Leftover>) :
        DialogWrapper(project) {
        init {
            title = "Still Using Gradle or Maven"
            setCancelButtonText("Close")
            init()
        }

        override fun createActions() = arrayOf(cancelAction)

        override fun createCenterPanel(): JComponent {
            val items = leftovers.map { l -> dir.relativize(l.file).toString() + (if (l.line > 0) ":${l.line}" else "") + "  " + l.what }
            val list = JBList(items)
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val l = leftovers.getOrNull(list.selectedIndex) ?: return
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(l.file) ?: return
                    OpenFileDescriptor(project, vf, maxOf(0, l.line - 1), 0).navigate(true)
                }
            })
            return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                add(JBLabel("Double-click to open. These still call Gradle or Maven, or are set up for them:"), BorderLayout.NORTH)
                add(JBScrollPane(list).apply { preferredSize = Dimension(JBUI.scale(720), JBUI.scale(320)) }, BorderLayout.CENTER)
            }
        }
    }
}
