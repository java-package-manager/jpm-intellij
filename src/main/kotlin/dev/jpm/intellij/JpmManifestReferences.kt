package dev.jpm.intellij

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.psi.impl.source.resolve.reference.impl.providers.JavaClassReferenceProvider
import com.intellij.psi.impl.source.resolve.reference.impl.providers.PackageReferenceSet
import com.intellij.util.ProcessingContext

/**
 * What the values in project.jsonc name, as references: ctrl+click goes there, and renaming or moving it updates the
 * manifest. Classes (`main`, `defineClass`), files and folders (`workspace`, `sources`, `resources`, `patches`,
 * script files), packages (`relocate`), scripts (`hooks`) and the license file (`license`).
 */
class JpmManifestReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(JsonStringLiteral::class.java), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val literal = element as? JsonStringLiteral ?: return PsiReference.EMPTY_ARRAY
                if (!JpmManifest.isManifest(literal.containingFile)) return PsiReference.EMPTY_ARRAY
                return JpmManifest.references(literal)
            }
        })
    }
}

/** Reading project.jsonc's PSI: where a value is, and what it names. */
object JpmManifest {
    fun isManifest(file: com.intellij.psi.PsiFile?): Boolean = file is JsonFile && file.name == Jpm.MANIFEST

    /** The keys from the top of the manifest down to a value; "#" stands for an array item, and a property's own name ends in "@key". */
    fun path(literal: JsonStringLiteral): List<String> {
        val out = ArrayList<String>()
        var e: PsiElement = literal
        val parent = e.parent
        if (parent is JsonProperty && parent.nameElement == literal) {
            out.add("@key")
            e = parent
            out.add(parent.name)
            e = parent.parent ?: return out.reversed()
        }
        while (e !is JsonFile) {
            val p = e.parent ?: break
            when (p) {
                is JsonProperty -> if (p.value == e) out.add(p.name)
                is JsonArray -> out.add("#")
            }
            e = p
        }
        return out.reversed()
    }

    private val classFields = setOf(listOf("main"), listOf("defineClass"))
    private val folderFields = setOf(listOf("workspace", "#"), listOf("sources", "#"), listOf("resources", "#"))

    /** Whether a value at this path names a file or folder, relative to the manifest. */
    fun isPath(path: List<String>, value: String): Boolean = path in folderFields
        || path.size == 2 && path[0] == "patches"
        || path.size == 3 && path[0] == "patches" && path[2] == "file"
        || scriptFile(path, value)

    private fun scriptFile(path: List<String>, value: String): Boolean {
        val isScript = path.size == 2 && path[0] == "scripts" || path.size == 3 && path[0] == "scripts" && path[2] == "run"
        return isScript && !value.contains(' ') && (value.endsWith(".java") || value.endsWith(".sh") || value.endsWith(".kts"))
    }

    fun references(literal: JsonStringLiteral): Array<PsiReference> {
        val value = literal.value
        if (value.isEmpty()) return PsiReference.EMPTY_ARRAY
        val path = path(literal)
        val inner = 1
        return when {
            path in classFields || path.size == 2 && path[0] == "main" ->
                JavaClassReferenceProvider().getReferencesByString(value, literal, inner)
            path.size == 3 && path[0] == "relocate" && path[2] == "@key" ->
                PackageReferenceSet(value, literal, inner).references.toTypedArray()
            isPath(path, value) -> FileReferenceSet(value, literal, inner, null, true).allReferences.map { it as PsiReference }.toTypedArray()
            path.size == 2 && path[0] == "hooks" -> arrayOf(ScriptReference(literal))
            path == listOf("license") -> arrayOf(LicenseReference(literal))
            else -> PsiReference.EMPTY_ARRAY
        }
    }

    /** The `scripts` entry a hook runs. */
    class ScriptReference(literal: JsonStringLiteral) : PsiReferenceBase<JsonStringLiteral>(literal, TextRange(1, literal.textLength - 1)) {
        override fun resolve(): PsiElement? {
            val root = (element.containingFile as? JsonFile)?.topLevelValue as? JsonObject ?: return null
            val scripts = root.findProperty("scripts")?.value as? JsonObject ?: return null
            return scripts.findProperty(element.value)
        }

        override fun getVariants(): Array<Any> {
            val root = (element.containingFile as? JsonFile)?.topLevelValue as? JsonObject ?: return emptyArray()
            val scripts = root.findProperty("scripts")?.value as? JsonObject ?: return emptyArray()
            return scripts.propertyList.map { it.name }.toTypedArray()
        }
    }

    /** The license file next to the manifest: LICENSE, LICENSE.md, LICENCE, COPYING and the like. */
    class LicenseReference(literal: JsonStringLiteral) : PsiReferenceBase<JsonStringLiteral>(literal, TextRange(1, literal.textLength - 1), true) {
        override fun resolve(): PsiElement? {
            val dir = element.containingFile.originalFile.containingDirectory ?: return null
            for (n in LICENSE_FILES) dir.findFile(n)?.let { return it }
            return null
        }

        override fun handleElementRename(newElementName: String): PsiElement = element
    }

    val LICENSE_FILES = listOf("LICENSE", "LICENSE.md", "LICENSE.txt", "LICENCE", "LICENCE.md", "LICENCE.txt", "COPYING", "COPYING.md", "COPYING.txt",
        "license", "license.md", "license.txt")
}

/**
 * Finds the references in project.jsonc files for find usages and rename: the word index does not see qualified
 * names and paths inside JSON strings, so every manifest in the search scope is looked through.
 */
class JpmManifestReferencesSearcher :
    com.intellij.openapi.application.QueryExecutorBase<PsiReference, com.intellij.psi.search.searches.ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(params: com.intellij.psi.search.searches.ReferencesSearch.SearchParameters,
                              consumer: com.intellij.util.Processor<in PsiReference>) {
        val target = params.elementToSearch
        val project = params.project
        val scope = params.effectiveSearchScope as? com.intellij.psi.search.GlobalSearchScope
            ?: com.intellij.psi.search.GlobalSearchScope.allScope(project)
        val psi = com.intellij.psi.PsiManager.getInstance(project)
        for (vf in com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(Jpm.MANIFEST, scope)) {
            val file = psi.findFile(vf) ?: continue
            for (literal in com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, JsonStringLiteral::class.java)) {
                for (ref in literal.references) {
                    if (ref.isReferenceTo(target) && !consumer.process(ref)) return
                }
            }
        }
    }
}
