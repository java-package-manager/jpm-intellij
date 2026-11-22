package dev.jpm.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.charset.StandardCharsets

/** Installs jpm for the user with jpm's own install script. */
object JpmInstaller {
    private const val BASE = "https://github.com/java-package-manager/jpm/releases/latest/download"

    /** The command that installs the latest jpm into ~/.jpm (the same one the README gives). */
    fun installCommand(): GeneralCommandLine =
        if (SystemInfo.isWindows) GeneralCommandLine("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "irm $BASE/install.ps1 | iex")
        else GeneralCommandLine("sh", "-c", "curl -fsSL $BASE/install.sh | sh")

    fun install(project: Project, then: () -> Unit) = run(project, "Installing jpm", installCommand(), then)

    private fun run(project: Project, title: String, cmd: GeneralCommandLine, then: () -> Unit) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val out = CapturingProcessHandler(cmd.withCharset(StandardCharsets.UTF_8)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)).runProcessWithProgressIndicator(indicator)
                val group = NotificationGroupManager.getInstance().getNotificationGroup("jpm")
                if (out.exitCode == 0 && Jpm.executable() != null) {
                    JpmSchemaProvider.forget()
                    group.createNotification("$title: done", notificationText(out.stdout.lines().lastOrNull { it.isNotBlank() }.orEmpty()),
                        NotificationType.INFORMATION).notify(project)
                    then()
                } else {
                    group.createNotification("$title failed", notificationText(out.stderr.ifBlank { out.stdout }), NotificationType.ERROR).notify(project)
                }
            }
        }.queue()
    }
}
