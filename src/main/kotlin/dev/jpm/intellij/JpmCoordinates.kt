package dev.jpm.intellij

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Maven coordinates for completion, from jpm's `dependencies/search`, kept per query for the session so that
 * typing on does not ask again.
 */
object JpmCoordinates {
    class Hit(val key: String, val latest: String?)

    const val LIMIT = 30

    private val cache = ConcurrentHashMap<String, List<Hit>>()

    /** How a query is answered; tests replace it, the plugin asks jpm. */
    @Volatile
    var search: (Path, String) -> List<Hit> = { dir, query -> if (Jpm.executable() == null) emptyList() else JpmApi.search(dir, query, LIMIT) }

    /** The packages for a query, asked once per query. A query needs at least two characters. */
    fun find(dir: Path, query: String): List<Hit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return cache.getOrPut(q) { search(dir, q) }
    }

    fun clear() = cache.clear()

    private val SCOPES = setOf("runtime", "dev", "test")

    /** Whether a literal is a dependency key in project.jsonc: a key in `runtime`, `dev` or `test`. */
    fun isDependencyKey(literal: JsonStringLiteral): Boolean {
        val path = JpmManifest.path(literal)
        return path.size == 3 && path[0] in SCOPES && path[2] == "@key"
    }
}

/** Completes a dependency key in project.jsonc with Maven coordinates that match what is typed. */
class JpmCoordinateCompletion : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inside(JsonStringLiteral::class.java), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                if (!JpmManifest.isManifest(parameters.originalFile)) return
                val literal = com.intellij.psi.util.PsiTreeUtil.getParentOfType(parameters.position, JsonStringLiteral::class.java, false) ?: return
                if (!JpmCoordinates.isDependencyKey(literal)) return
                val start = literal.textRange.startOffset + 1
                val typed = parameters.editor.document.charsSequence.subSequence(start, maxOf(start, parameters.offset)).toString()
                if (typed.startsWith("workspace:") || typed.contains('/')) return
                val dir = parameters.originalFile.virtualFile?.parent?.let { it.fileSystem.getNioPath(it) }
                    ?: parameters.originalFile.project.basePath?.let { Path.of(it) } ?: return
                val hits = JpmCoordinates.find(dir, typed)
                val matching = result.withPrefixMatcher(typed)
                hits.forEachIndexed { i, h ->
                    val item = LookupElementBuilder.create(h.key).withTypeText(h.latest ?: "", true)
                    matching.addElement(PrioritizedLookupElement.withPriority(item, (hits.size - i).toDouble()))
                }
            }
        })
    }
}
