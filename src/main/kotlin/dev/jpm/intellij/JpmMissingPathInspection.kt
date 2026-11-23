package dev.jpm.intellij

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.psi.JsonElementVisitor
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElementVisitor

/**
 * One check for every value in project.jsonc that names a file or folder (workspace modules, sources, resources,
 * patches, script files): a warning when it does not exist.
 */
class JpmMissingPathInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!JpmManifest.isManifest(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
        val dir = holder.file.originalFile.virtualFile?.parent ?: return PsiElementVisitor.EMPTY_VISITOR
        return object : JsonElementVisitor() {
            override fun visitStringLiteral(literal: JsonStringLiteral) {
                val value = literal.value
                if (value.isBlank() || !JpmManifest.isPath(JpmManifest.path(literal), value)) return
                if (dir.findFileByRelativePath(value.trimEnd('/')) != null) return
                val what = if (JpmManifest.path(literal).firstOrNull() in setOf("workspace", "sources", "resources")) "folder" else "file"
                holder.registerProblem(literal, "The $what $value does not exist", ProblemHighlightType.WARNING)
            }
        }
    }
}
