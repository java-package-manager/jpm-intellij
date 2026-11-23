package dev.jpm.intellij

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonElementVisitor
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElementVisitor
import com.intellij.util.ProcessingContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The versions of the project's Maven dependencies, asked from jpm in the background after each load and kept for
 * the session, so completion and the inspection for newer versions never wait for the network.
 */
object JpmVersions {
    class Known(val versions: List<String>, val latest: String?)

    private val known = ConcurrentHashMap<String, Known>()

    fun get(key: String): Known? = known[key]

    /** Asks jpm about every Maven dependency of these projects that is not known yet, then highlights again. */
    fun refresh(project: Project, infos: List<JpmProjectInfo>) {
        if (Jpm.executable() == null) return
        ApplicationManager.getApplication().executeOnPooledThread {
            var changed = false
            for (info in infos) {
                val keys = info.modules.flatMap { it.dependencies.orEmpty() }.filter { it.kind == "maven" && !known.containsKey(it.key) }.map { it.key }.distinct()
                if (keys.isEmpty()) continue
                try {
                    val found = JpmApi.versions(Path.of(info.projectDir), keys)
                    known.putAll(found)
                    changed = changed || found.isNotEmpty()
                } catch (e: Exception) {
                    continue
                }
            }
            if (changed && !project.isDisposed) ApplicationManager.getApplication().invokeLater({ DaemonCodeAnalyzer.getInstance(project).restart() }, project.disposed)
        }
    }

    fun remember(key: String, k: Known) {
        known[key] = k
    }

    /** The dependency key when a literal is a dependency's version in project.jsonc, else null. */
    fun dependencyOfVersion(literal: JsonStringLiteral): String? {
        val path = JpmManifest.path(literal)
        val ok = path.size == 2 && path[0] in JpmResolvedVersionHints.SCOPES || path.size == 3 && path[0] in JpmResolvedVersionHints.SCOPES && path[2] == "version"
        return if (ok && path[1].contains(':') && !path[1].contains('/')) path[1] else null
    }

    /** Whether a newer release than the version a dependency uses is known: the release, else null. */
    fun newer(current: String?, k: Known?): String? {
        val latest = k?.latest ?: return null
        if (current == null || current == latest) return null
        val idx = k.versions.indexOf(current)
        return if (idx >= 0 && k.versions.indexOf(latest) > idx) latest else null
    }
}

/** Completes the version of a dependency in project.jsonc with the versions its repositories have, newest first. */
class JpmVersionCompletion : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inside(JsonStringLiteral::class.java), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                if (!JpmManifest.isManifest(parameters.originalFile)) return
                val literal = com.intellij.psi.util.PsiTreeUtil.getParentOfType(parameters.position, JsonStringLiteral::class.java, false) ?: return
                val key = JpmVersions.dependencyOfVersion(literal) ?: return
                val k = JpmVersions.get(key) ?: return
                k.versions.reversed().forEachIndexed { i, v ->
                    val item = LookupElementBuilder.create(v).withTypeText(if (v == k.latest) "latest release" else "")
                    result.addElement(PrioritizedLookupElement.withPriority(item, (k.versions.size - i).toDouble()))
                }
                for (spec in listOfNotNull(k.latest?.let { "^$it" }, k.latest?.let { "~$it" })) result.addElement(LookupElementBuilder.create(spec))
            }
        })
    }
}

/** A dependency with a newer release than the one it resolves to; the fixes write it, or run `jpm update`. */
class JpmNewerVersionInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!JpmManifest.isManifest(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
        val dir = holder.file.originalFile.virtualFile?.parent?.let { it.fileSystem.getNioPath(it) }
        val resolved = dir?.let { JpmResolvedVersionHints.resolved(JpmSync.get(holder.project).infos.orEmpty(), it) }.orEmpty()
        return object : JsonElementVisitor() {
            override fun visitStringLiteral(literal: JsonStringLiteral) {
                val key = JpmVersions.dependencyOfVersion(literal) ?: return
                val scope = JpmManifest.path(literal)[0]
                val current = resolved[scope to key] ?: literal.value
                val latest = JpmVersions.newer(current, JpmVersions.get(key)) ?: return
                holder.registerProblem(literal, "$key $latest is available (this resolves to $current)", ProblemHighlightType.WEAK_WARNING,
                    UseVersion(latest), RunUpdate(key, dir))
            }
        }
    }

    class UseVersion(private val version: String) : LocalQuickFix {
        override fun getFamilyName() = "Use version $version"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val literal = descriptor.psiElement as? JsonStringLiteral ?: return
            literal.replace(JsonElementGenerator(project).createStringLiteral(version))
        }
    }

    class RunUpdate(private val key: String, private val dir: Path?) : LocalQuickFix {
        override fun getFamilyName() = "Run jpm update $key --latest"

        override fun startInWriteAction() = false

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            JpmConfigurationType.run(project, listOf("update", key, "--latest"), dir)
        }
    }
}
