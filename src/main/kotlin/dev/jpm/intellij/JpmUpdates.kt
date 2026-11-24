package dev.jpm.intellij

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Once per IDE session, asks jpm whether it has a newer release (`jpm upgrade --check`, with `--canary` on the
 * canary channel) and whether its global extensions have newer releases in their ranges (`jpm update -g --check`),
 * then installs them when [JpmSettingsState.autoUpdate] is on, or offers them in a notification.
 */
object JpmUpdates {
    private val log = logger<JpmUpdates>()
    private val checked = AtomicBoolean()

    data class JpmUpdate(val version: String, val current: String)

    data class ExtensionUpdate(val name: String, val current: String, val version: String)

    data class Found(val jpm: JpmUpdate?, val extensions: List<ExtensionUpdate>) {
        val isEmpty: Boolean get() = jpm == null && extensions.isEmpty()
    }

    private val ansi = Regex("\u001B\\[[0-9;]*m")
    private val newerJpm = Regex("""jpm (\S+) is (?:available|the newest prerelease or release) \(you have ([^)\s]+)\)""")
    private val outdated = Regex("""^\s*Outdated jpm-(\S+) (.+?) -> (\S+)\s*$""")

    /** The newer jpm that the output of `jpm upgrade --check` names, or null when it names none. */
    fun parseUpgradeCheck(output: String): JpmUpdate? =
        newerJpm.find(ansi.replace(output, ""))?.let { JpmUpdate(it.groupValues[1], it.groupValues[2]) }

    /** The global extensions that the output of `jpm update -g --check` has updates for. */
    fun parseExtensionCheck(output: String): List<ExtensionUpdate> =
        ansi.replace(output, "").lines().mapNotNull { line ->
            outdated.find(line)?.let { ExtensionUpdate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        }

    /** Whether jpm rejected `--check` for `jpm update -g`: it is older than the jpm that has it. */
    fun lacksExtensionCheck(output: String): Boolean = output.contains("Unknown option --check")

    fun upgradeArgs(canary: Boolean, check: Boolean): List<String> =
        listOf("upgrade") + listOfNotNull("--check".takeIf { check }, "--canary".takeIf { canary }) + "--color=never"

    /** Starts the check in the background the first time a project opens in this IDE session. */
    fun checkOnStartup(project: Project) {
        if (Jpm.executable() == null || !checked.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                check(project)
            } catch (e: Exception) {
                log.info("Could not check jpm for updates", e)
            }
        }
    }

    private fun home(): Path = Path.of(System.getProperty("user.home"))

    private fun jpm(args: List<String>) = Jpm.run(home(), args, null).let { it.exitCode to it.stdout + it.stderr }

    fun find(): Found {
        val canary = JpmSettings.get().state.canary
        val (code, upgrade) = jpm(upgradeArgs(canary, check = true))
        val jpm = if (code == 0) parseUpgradeCheck(upgrade) else null
        if (code != 0) log.info("jpm upgrade --check exited with $code: $upgrade")
        val (extCode, ext) = jpm(listOf("update", "-g", "--check", "--color=never"))
        val extensions = if (extCode == 0) parseExtensionCheck(ext) else emptyList()
        if (extCode != 0 && !lacksExtensionCheck(ext)) log.info("jpm update -g --check exited with $extCode: $ext")
        return Found(jpm, extensions)
    }

    private fun check(project: Project) {
        val found = find()
        if (found.isEmpty || project.isDisposed) return
        if (JpmSettings.get().state.autoUpdate) install(project, found) else offer(project, found)
    }

    fun describe(found: Found): String = buildList {
        found.jpm?.let { add("jpm ${it.version} (you have ${it.current})") }
        found.extensions.forEach { add("jpm-${it.name} ${it.version} (you have ${it.current})") }
    }.joinToString("<br>")

    private fun offer(project: Project, found: Found) {
        val title = if (found.jpm != null) "jpm ${found.jpm.version} is available" else "Updates for global jpm extensions"
        NotificationGroupManager.getInstance().getNotificationGroup("jpm")
            .createNotification(title, describe(found), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Update") { install(project, found) })
            .addAction(NotificationAction.createSimple("Settings…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, JpmConfigurable::class.java)
            })
            .notify(project)
    }

    /** Upgrades jpm and updates the global extensions that [found] names, in the background. */
    fun install(project: Project, found: Found) {
        object : Task.Backgroundable(project, "Updating jpm", true) {
            override fun run(indicator: ProgressIndicator) {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("jpm")
                val done = ArrayList<String>()
                if (found.jpm != null) {
                    indicator.text = "Upgrading jpm to ${found.jpm.version}"
                    val out = Jpm.run(home(), upgradeArgs(JpmSettings.get().state.canary, check = false), indicator)
                    if (out.exitCode != 0) {
                        group.createNotification("jpm upgrade failed", notificationText(out.stderr.ifBlank { out.stdout }),
                            NotificationType.ERROR).notify(project)
                        return
                    }
                    JpmSchemaProvider.forget()
                    JpmSchemaProvider.update()
                    done.add("jpm ${found.jpm.version}")
                }
                if (found.extensions.isNotEmpty()) {
                    indicator.text = "Updating global jpm extensions"
                    val out = Jpm.run(home(), listOf("update", "-g", "--color=never"), indicator)
                    if (out.exitCode != 0) {
                        group.createNotification("jpm update -g failed", notificationText(out.stderr.ifBlank { out.stdout }),
                            NotificationType.ERROR).notify(project)
                        return
                    }
                    found.extensions.forEach { done.add("jpm-${it.name} ${it.version}") }
                }
                group.createNotification("jpm updated", done.joinToString("<br>"), NotificationType.INFORMATION).notify(project)
            }
        }.queue()
    }
}
