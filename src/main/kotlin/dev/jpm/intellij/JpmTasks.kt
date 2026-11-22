package dev.jpm.intellij

import java.nio.file.Path

/** A jpm command offered in the tool window: `jpm <args>` run in [dir]. */
data class JpmTask(val label: String, val args: List<String>, val hint: String?, val dir: Path)

data class JpmTaskGroup(val name: String, val tasks: List<JpmTask>)

/**
 * The commands that can actually run for a project or module: no test task without tests, no start, dev
 * or native build without a main (a bundle without one is a flat jar, as plugins need), and the commands of
 * the extensions the project can run, as their manifests describe them (jpm's extensions/list).
 */
object JpmTasks {
    /** The tasks of a whole jpm project (in a workspace: of every module, run from its root). */
    fun forProject(info: JpmProjectInfo): List<JpmTaskGroup> {
        val dir = Path.of(info.projectDir)
        val groups = ArrayList<JpmTaskGroup>()

        val lifecycle = ArrayList<JpmTask>()
        lifecycle.add(JpmTask("build", listOf("build"), "compile and package the jars", dir))
        if (info.modules.any { it.hasTests }) lifecycle.add(JpmTask("test", listOf("test"), "run the tests", dir))
        val runnable = info.modules.filter { !it.main.isNullOrEmpty() }
        if (runnable.size == 1) lifecycle.addAll(runTasks(runnable[0], Path.of(runnable[0].dir)))
        lifecycle.add(JpmTask("clean", listOf("clean"), "remove build output", dir))
        groups.add(JpmTaskGroup("Lifecycle", lifecycle))

        val build = ArrayList<JpmTask>()
        build.add(JpmTask("build --release", listOf("build", "--release"), "with the release profile", dir))
        build.add(JpmTask("build --bundle", listOf("build", "--bundle"),
            if (runnable.isEmpty()) "one jar with the runtime dependencies merged in" else "with the runtime dependencies", dir))
        if (runnable.isNotEmpty()) build.add(JpmTask("build --native", listOf("build", "--native"), "GraalVM native executable", dir))
        groups.add(JpmTaskGroup("Build", build))

        groups.add(JpmTaskGroup("Dependencies", listOf(
            JpmTask("install", listOf("install"), "resolve and download", dir),
            JpmTask("update", listOf("update"), "newest versions within the ranges", dir),
            JpmTask("outdated", listOf("outdated"), "show newer versions", dir),
        )))

        for (e in info.extensionList) {
            val tasks = e.commands.map { c -> JpmTask(c.name, c.name.split(" ").filter { it.isNotEmpty() }, c.description, dir) }
            if (tasks.isNotEmpty()) groups.add(JpmTaskGroup("jpm-${e.name}" + if (e.source == "global") " (global)" else "", tasks))
        }

        val scripts = if (info.workspace) info.scripts.orEmpty() else (info.scripts.orEmpty() + info.modules.flatMap { it.scripts.orEmpty() }).distinct()
        if (scripts.isNotEmpty()) groups.add(JpmTaskGroup("Scripts", scripts.map { JpmTask(it, listOf("run", it), "jpm run $it", dir) }))
        return groups
    }

    /** The tasks of one workspace module, run in its folder (jpm then acts on that module only). */
    fun forModule(info: JpmProjectInfo, m: JpmModuleInfo): List<JpmTask> {
        val dir = Path.of(m.dir)
        val out = ArrayList<JpmTask>()
        out.add(JpmTask("build", listOf("build"), null, dir))
        if (m.hasTests) out.add(JpmTask("test", listOf("test"), null, dir))
        if (!m.main.isNullOrEmpty()) out.addAll(runTasks(m, dir))
        out.add(JpmTask("build --bundle", listOf("build", "--bundle"), if (m.main.isNullOrEmpty()) "flat jar with the runtime dependencies" else null, dir))
        if (!m.main.isNullOrEmpty()) out.add(JpmTask("build --native", listOf("build", "--native"), null, dir))
        for (s in m.scripts.orEmpty()) out.add(JpmTask("run $s", listOf("run", s), null, dir))
        return out
    }

    private fun runTasks(m: JpmModuleInfo, dir: Path): List<JpmTask> {
        val entries = m.main.orEmpty()
        val out = ArrayList<JpmTask>()
        if (entries.keys == setOf("main")) {
            out.add(JpmTask("start", listOf("start"), entries["main"], dir))
            out.add(JpmTask("dev", listOf("dev"), "run and restart on changes", dir))
        } else {
            for ((e, cls) in entries) {
                val args = if (e == "main") emptyList() else listOf(e)
                out.add(JpmTask("start" + (if (e == "main") "" else " $e"), listOf("start") + args, cls, dir))
                out.add(JpmTask("dev" + (if (e == "main") "" else " $e"), listOf("dev") + args, "run $e and restart on changes", dir))
            }
        }
        return out
    }
}
