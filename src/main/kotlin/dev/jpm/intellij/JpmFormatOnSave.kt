package dev.jpm.intellij

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Runs `jpm format --stdin <file>` on the Java files of jpm projects when they are saved, if
 * [JpmSettingsState.formatOnSave] is on, and puts jpm-format's output into the document.
 */
class JpmFormatOnSave : ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave() {
    override val presentableName: String = "jpm format"

    override fun isEnabledForProject(project: Project): Boolean =
        JpmSettings.get().state.formatOnSave && Jpm.isJpmProject(project)

    override suspend fun updateDocument(project: Project, document: Document) {
        val (path, text, stamp) = readAction {
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@readAction null
            if (!file.isInLocalFileSystem || !isFormatted(file.name)) return@readAction null
            Triple(file.toNioPath(), document.text, document.modificationStamp)
        } ?: return
        val dir = rootFor(path, JpmSync.get(project).roots()) ?: return
        val formatted = withContext(Dispatchers.IO) { format(project, dir, path, text) } ?: return
        if (formatted == text) return
        edtWriteAction {
            if (document.modificationStamp != stamp || project.isDisposed) return@edtWriteAction
            val edit = edit(text, formatted)
            CommandProcessor.getInstance().executeCommand(project, {
                document.replaceString(edit.start, edit.end, edit.replacement)
            }, "jpm format", null)
        }
    }

    private fun format(project: Project, dir: Path, path: Path, text: String): String? {
        val cmd = try {
            Jpm.commandLine(dir, listOf("format", "--stdin", path.toString()))
        } catch (e: JpmNotFoundException) {
            warnOnce(project, e.message.orEmpty())
            return null
        }
        val handler = CapturingProcessHandler(cmd)
        handler.processInput.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
        val out = handler.runProcess(TIMEOUT_MS)
        if (out.isTimeout || out.isCancelled) return null
        if (out.exitCode == 0) return out.stdout
        if (out.stdout.isEmpty()) {
            log.info("jpm format --stdin $path exited with ${out.exitCode}: ${out.stderr}")
            warnOnce(project, out.stderr)
        }
        return null
    }

    private fun warnOnce(project: Project, message: String) {
        if (warned) return
        warned = true
        NotificationGroupManager.getInstance().getNotificationGroup("jpm")
            .createNotification("jpm format on save failed", notificationText(message), NotificationType.WARNING)
            .notify(project)
    }

    /** One replacement that turns [old] into [new]: the part between their common prefix and suffix. */
    data class Edit(val start: Int, val end: Int, val replacement: String)

    companion object {
        private val log = logger<JpmFormatOnSave>()
        private const val TIMEOUT_MS = 30_000

        @Volatile
        private var warned = false

        fun isFormatted(fileName: String): Boolean = fileName.endsWith(".java")

        /** The innermost jpm project folder among [roots] that holds [file], or null when none does. */
        fun rootFor(file: Path, roots: List<Path>): Path? =
            roots.filter { file.startsWith(it) }.maxByOrNull { it.nameCount }

        fun edit(old: String, new: String): Edit {
            var prefix = 0
            val max = minOf(old.length, new.length)
            while (prefix < max && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            return Edit(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
        }
    }
}
