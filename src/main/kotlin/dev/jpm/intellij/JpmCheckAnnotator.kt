package dev.jpm.intellij

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * jpm's own warnings and errors about a project.jsonc, at the line they are about: what `jpm check` reports for the
 * manifest, the dependencies and the license, asked over the protocol once the file is saved. Paths that do not exist
 * are [JpmMissingPathInspection]'s, so they are not asked for twice.
 */
class JpmCheckAnnotator : ExternalAnnotator<JpmCheckAnnotator.Input, List<JpmCheckAnnotator.Found>>() {
    class Input(val manifest: Path, val stamp: Long)

    class Found(val line: Int, val severity: String, val message: String)

    override fun collectInformation(file: PsiFile): Input? {
        if (!JpmManifest.isManifest(file)) return null
        val vf = file.originalFile.virtualFile ?: return null
        if (FileDocumentManager.getInstance().isFileModified(vf)) return null
        val path = vf.fileSystem.getNioPath(vf) ?: return null
        if (Jpm.executable() == null) return null
        return Input(path, vf.modificationStamp)
    }

    override fun doAnnotate(input: Input): List<Found> {
        val key = input.manifest.toAbsolutePath().normalize()
        cache[key]?.takeIf { it.first == input.stamp }?.let { return it.second }
        val found = try {
            JpmApi.check(key.parent, CHECKS).filter { p -> p.file() == null || p.file().toAbsolutePath().normalize() == key }
                .map { Found(it.line() ?: 1, it.severity() ?: "error", it.message() + (it.hint()?.let { h -> " ($h)" } ?: "")) }
        } catch (e: Exception) {
            emptyList()
        }
        cache[key] = input.stamp to found
        return found
    }

    override fun apply(file: PsiFile, found: List<Found>, holder: AnnotationHolder) {
        val doc = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for (f in found) {
            val line = (f.line - 1).coerceIn(0, maxOf(0, doc.lineCount - 1))
            val start = doc.getLineStartOffset(line)
            val end = doc.getLineEndOffset(line)
            val text = doc.charsSequence.subSequence(start, end)
            val from = start + text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val to = start + text.trimEnd().length
            val range = if (to > from) TextRange(from, to) else TextRange(start, end)
            val severity = if (f.severity == "warning") HighlightSeverity.WARNING else HighlightSeverity.ERROR
            holder.newAnnotation(severity, "jpm: " + f.message).range(range).create()
        }
    }

    companion object {
        val CHECKS = listOf("manifest", "dependencies", "license")
        private val cache = ConcurrentHashMap<Path, Pair<Long, List<Found>>>()
    }
}
