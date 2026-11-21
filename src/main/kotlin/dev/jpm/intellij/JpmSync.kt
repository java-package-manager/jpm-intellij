package dev.jpm.intellij

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Loads the jpm project into IntelliJ, on request or when its manifests change. */
@Service(Service.Level.PROJECT)
class JpmSync(private val project: Project) : Disposable {
    /** Each jpm project as jpm last reported it, or null before the first load. */
    @Volatile
    var infos: List<JpmProjectInfo>? = null
        private set

    /** The first jpm project, for callers that need just one. */
    val info: JpmProjectInfo? get() = infos?.firstOrNull()

    @Volatile
    private var cachedRoots: List<Path>? = null

    /** The last successful load of each project folder, kept for projects that fail to load later. */
    @Volatile
    private var previous: Map<Path, JpmProjectInfo> = emptyMap()

    /** The folders of the jpm projects in this project (see [Jpm.findRoots]), searched once and after changes. */
    fun roots(): List<Path> {
        cachedRoots?.let { return it }
        val found = Jpm.baseDir(project)?.let { Jpm.findRoots(it) }.orEmpty()
        cachedRoots = found
        return found
    }

    /** A project.jsonc was added, removed or moved: search for jpm projects again on the next use. */
    fun rootsChanged() {
        cachedRoots = null
    }

    /** The last load's error, shown in the tool window. */
    @Volatile
    var error: String? = null
        private set

    @Volatile
    var loading: Boolean = false
        private set

    private val running = AtomicBoolean(false)

    /** The progress of the running load, so [stop] can cancel it. */
    @Volatile
    private var indicator: ProgressIndicator? = null

    @Volatile
    private var stopped = false
    private val again = AtomicBoolean(false)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var lastInputs: String? = null

    /** Loads the project now. Explicit reloads always run; automatic ones only when the manifests changed. */
    fun reload(explicit: Boolean = true) {
        if (stopped || project.isDisposed || roots().isEmpty()) return
        if (!explicit && inputsHash() == lastInputs && infos != null) return
        if (!running.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        loading = true
        publish()
        object : Task.Backgroundable(project, "Loading jpm project", true) {
            override fun run(indicator: ProgressIndicator) {
                this@JpmSync.indicator = indicator
                // the schema of project.jsonc comes from the jpm that loads the project
                JpmSchemaProvider.update()
                val roots = roots()
                val loaded = collectInfos(roots, previous) { dir ->
                    indicator.checkCanceled()
                    indicator.text = "jpm info in ${relative(dir)}"
                    val out = Jpm.run(dir, listOf("info", "--json", "--ide"), indicator)
                    indicator.checkCanceled()
                    if (out.exitCode != 0) {
                        throw LoadException(out.stderr.ifBlank { out.stdout }.trim().lines().filterNot { it.startsWith("Picked up ") }.joinToString("\n"))
                    }
                    JpmProjectInfo.parse(out.stdout).also { info -> info.modules.forEach { it.hasTests = hasTestSources(it) } }
                }
                val parsed = loaded.infos
                if (parsed.isEmpty() && loaded.failures.isNotEmpty()) {
                    fail(loaded.failures.joinToString("\n"))
                    return
                }
                ApplicationManager.getApplication().invokeAndWait({
                    if (!project.isDisposed) JpmProjectSetup.apply(project, parsed)
                }, com.intellij.openapi.application.ModalityState.nonModal())
                infos = parsed
                previous = loaded.byRoot
                error = loaded.failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
                if (loaded.failures.isNotEmpty()) {
                    notify("Some jpm projects could not be loaded", loaded.failures.joinToString("\n") +
                        "\nThey keep their last loaded state until they load again.", NotificationType.WARNING,
                        listOf(NotificationAction.createSimple("Reload") { reload() }))
                }
                // jpm info may have written project.lock; that is part of this load, not a new change.
                lastInputs = inputsHash()
                parsed.flatMap { it.modules }.mapNotNull { m -> m.javaError?.let { "${m.name}: $it" } }.takeIf { it.isNotEmpty() }?.let {
                    notify("The JDK for this project is not available", it.joinToString("\n"), NotificationType.WARNING)
                }
            }

            override fun onThrowable(error: Throwable) {
                if (error is JpmNotFoundException) fail(error.message ?: "jpm is not installed")
                else {
                    logger<JpmSync>().warn(error)
                    fail(error.message ?: error.toString())
                }
            }

            override fun onFinished() {
                indicator = null
                loading = false
                running.set(false)
                publish()
                if (again.getAndSet(false)) reload(explicit = false)
            }
        }.queue()
    }

    /** Reloads shortly after a change, so a burst of file events loads once. */
    fun scheduleReload() {
        if (!JpmSettings.get().state.autoReload) return
        alarm.cancelAllRequests()
        alarm.addRequest({ reload(explicit = false) }, 500)
    }

    private fun fail(message: String) {
        error = message
        val actions = if (Jpm.executable() == null) {
            listOf(NotificationAction.createSimple("Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, JpmConfigurable::class.java)
            })
        } else {
            listOf(NotificationAction.createSimple("Reload") { reload() })
        }
        notify("Could not load the jpm project", message, NotificationType.ERROR, actions)
    }

    private fun notify(title: String, content: String, type: NotificationType, actions: List<NotificationAction> = emptyList()) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup("jpm").createNotification(title, notificationText(content), type)
        actions.forEach { n.addAction(it) }
        n.notify(project)
    }

    private fun publish() {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).changed()
        }, project.disposed)
    }

    /** A hash of every manifest and lockfile of the project, to see whether a reload would change anything. */
    private fun inputsHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val files = buildList {
            for (dir in roots()) {
                add(dir.resolve(Jpm.MANIFEST))
                add(dir.resolve(Jpm.LOCKFILE))
            }
            infos?.forEach { i -> i.modules.forEach { add(Path.of(it.dir).resolve(Jpm.MANIFEST)) } }
        }.distinct()
        for (f in files) {
            md.update(f.toString().toByteArray())
            if (Files.isRegularFile(f)) md.update(Files.readAllBytes(f))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    class LoadException(message: String) : RuntimeException(message)



    class Loaded(val infos: List<JpmProjectInfo>, val byRoot: Map<Path, JpmProjectInfo>, val failures: List<String>)

    /**
     * Loads each jpm project among [roots] once: a folder that a project loaded before it covers (its
     * workspace's modules) is not loaded again. A project that fails to load keeps its [previous] load, so
     * its modules stay, and is reported in [Loaded.failures].
     */
    fun collectInfos(roots: List<Path>, previous: Map<Path, JpmProjectInfo>, load: (Path) -> JpmProjectInfo): Loaded {
        val covered = HashSet<Path>()
        val infos = ArrayList<JpmProjectInfo>()
        val byRoot = LinkedHashMap<Path, JpmProjectInfo>()
        val failures = ArrayList<String>()
        fun norm(p: Path): Path = try { p.toRealPath() } catch (e: java.io.IOException) { p.toAbsolutePath().normalize() }
        for (dir in roots) {
            val key = norm(dir)
            if (key in covered) continue
            val info = try {
                load(dir)
            } catch (e: LoadException) {
                failures.add((if (roots.size > 1) relative(dir) + ": " else "") + e.message)
                previous[key]
            } ?: continue
            val projectDir = norm(Path.of(info.projectDir))
            if (infos.any { norm(Path.of(it.projectDir)) == projectDir }) continue
            covered.add(key)
            covered.add(projectDir)
            info.modules.forEach { covered.add(norm(Path.of(it.dir))) }
            infos.add(info)
            byRoot[key] = info
        }
        return Loaded(infos, byRoot, failures)
    }

    /** A folder as shown to the user: relative to the project folder. */
    fun relative(dir: Path): String {
        val base = Jpm.baseDir(project) ?: return dir.toString()
        return if (dir == base) dir.fileName?.toString() ?: dir.toString() else base.relativize(dir).toString()
    }

    /** Stops loading for good: the plugin is being unloaded. */
    fun stop() {
        stopped = true
        alarm.cancelAllRequests()
        indicator?.cancel()
    }

    override fun dispose() {
        stop()
    }

    fun interface Listener {
        fun changed()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("jpm project", Listener::class.java)

        fun get(project: Project): JpmSync = project.service()

        /** True if the module has .java files in its test folder (outside its test resources). */
        fun hasTestSources(m: JpmModuleInfo): Boolean {
            val dir = m.testDir?.let { Path.of(it) } ?: return false
            if (!Files.isDirectory(dir)) return false
            val resources = m.testResourceDir?.let { Path.of(it) }
            return try {
                Files.walk(dir).use { s -> s.anyMatch { it.toString().endsWith(".java") && (resources == null || !it.startsWith(resources)) && Files.isRegularFile(it) } }
            } catch (e: java.io.IOException) {
                false
            }
        }
    }
}
