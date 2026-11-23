package dev.jpm.intellij

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.nio.file.Path

/** The version project.lock has for each dependency, next to its range in project.jsonc, when the two differ. */
class JpmResolvedVersionHints : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!JpmManifest.isManifest(file)) return null
        val dir = file.originalFile.virtualFile?.parent?.fileSystem?.getNioPath(file.originalFile.virtualFile.parent) ?: return null
        val deps = resolved(JpmSync.get(file.project).infos.orEmpty(), dir)
        if (deps.isEmpty()) return null
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                val literal = element as? JsonStringLiteral ?: return
                val version = hint(JpmManifest.path(literal), literal.value, deps) ?: return
                sink.addPresentation(InlineInlayPosition(literal.textRange.endOffset, true), hasBackground = true) { text(version) }
            }
        }
    }

    companion object {
        val SCOPES = setOf("runtime", "dev", "test")

        /** What each dependency of the module in [dir] resolved to, by scope and key. */
        fun resolved(infos: List<JpmProjectInfo>, dir: Path): Map<Pair<String, String>, String> {
            val norm = dir.toAbsolutePath().normalize()
            val m = infos.flatMap { it.modules }.firstOrNull { Path.of(it.dir).toAbsolutePath().normalize() == norm } ?: return emptyMap()
            return m.dependencies.orEmpty().mapNotNull { d -> d.resolved?.let { (d.scope.orEmpty() to d.key) to it } }.toMap()
        }

        /** The hint for a value: the resolved version of the dependency whose version it is, when that is not the value itself. */
        fun hint(path: List<String>, value: String, deps: Map<Pair<String, String>, String>): String? {
            val isVersion = path.size == 2 && path[0] in SCOPES || path.size == 3 && path[0] in SCOPES && path[2] == "version"
            if (!isVersion) return null
            val resolved = deps[path[0] to path[1]] ?: return null
            return if (resolved == value) null else "→ $resolved"
        }
    }
}
