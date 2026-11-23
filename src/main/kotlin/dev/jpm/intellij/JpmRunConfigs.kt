package dev.jpm.intellij

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil

/**
 * Run configurations made from the project: one per `main` entry (`jpm start <entry>`) and one per script
 * (`jpm run <script>`), kept in step with project.jsonc on every load. They are marked as generated, so the ones
 * a user made, or turned into their own by editing them, are never touched.
 */
object JpmRunConfigs {
    data class Spec(val name: String, val args: List<String>, val dir: String)

    fun wanted(infos: List<JpmProjectInfo>): List<Spec> {
        val out = ArrayList<Spec>()
        for (info in infos) {
            for (m in info.modules) {
                val prefix = if (info.workspace) "${m.name}: " else ""
                val mains = m.main.orEmpty()
                for (entry in mains.keys) {
                    val args = if (entry == "main") listOf("start") else listOf("start", entry)
                    out.add(Spec(prefix + "jpm " + ParametersListUtil.join(args), args, m.dir))
                }
                for (s in m.scripts.orEmpty()) out.add(Spec(prefix + "jpm run $s", listOf("run", s), m.dir))
            }
            if (info.workspace) {
                val own = info.modules.flatMap { it.scripts.orEmpty() }.toSet()
                for (s in info.scripts.orEmpty()) if (s !in own) out.add(Spec("${info.name}: jpm run $s", listOf("run", s), info.projectDir))
            }
        }
        return out.distinctBy { it.name }
    }

    /** Adds the run configurations the project wants and removes generated ones it no longer has. Runs on the EDT. */
    fun sync(project: Project, infos: List<JpmProjectInfo>) {
        val runManager = RunManager.getInstance(project)
        val type = JpmConfigurationType.get()
        val specs = wanted(infos)
        val names = specs.map { it.name }.toSet()
        for (settings in runManager.getConfigurationSettingsList(type)) {
            val c = settings.configuration as? JpmRunConfiguration ?: continue
            if (c.generated && settings.name !in names) runManager.removeConfiguration(settings)
        }
        for (spec in specs) {
            val existing = runManager.findConfigurationByTypeAndName(type, spec.name)
            if (existing != null) {
                val c = existing.configuration as? JpmRunConfiguration ?: continue
                if (c.generated) {
                    c.arguments = ParametersListUtil.join(spec.args)
                    c.workingDirectory = spec.dir
                }
                continue
            }
            val settings = runManager.createConfiguration(spec.name, type)
            val c = settings.configuration as JpmRunConfiguration
            c.arguments = ParametersListUtil.join(spec.args)
            c.workingDirectory = spec.dir
            c.generated = true
            runManager.addConfiguration(settings)
        }
    }
}
