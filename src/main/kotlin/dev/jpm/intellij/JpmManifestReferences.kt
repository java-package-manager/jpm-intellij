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
 * script files), packages (`relocate`), scripts (`hooks`), the license file (`license`), the JDK (`java`), the resources
 * a `filter` glob matches, the library of a dependency or exclusion, and the define a dependency declares.
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
            path == listOf("java") -> arrayOf(JdkReference(literal))
            path == listOf("filter", "#") -> arrayOf(FilterReference(literal))
            isDependencyKey(path) || isExclusion(path) -> arrayOf(LibraryReference(literal))
            path.size >= 3 && path[0] == "define" && path[1].contains(':') && (path.size == 3 || path.size == 4 && path[3] == "@key") ->
                arrayOf(DependencyDefineReference(literal, path[1], path[2]))
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

    private val SCOPES = setOf("runtime", "dev", "test")

    private fun isDependencyKey(path: List<String>) = path.size == 3 && path[0] in SCOPES && path[2] == "@key"

    private fun isExclusion(path: List<String>) = path == listOf("exclude", "#") || path.size == 4 && path[0] in SCOPES && path[2] == "exclude" && path[3] == "#"

    private fun range(literal: JsonStringLiteral) = TextRange(1, maxOf(1, literal.textLength - 1))

    /** The IntelliJ module the manifest belongs to. */
    private fun module(literal: JsonStringLiteral): com.intellij.openapi.module.Module? {
        val file = literal.containingFile.originalFile.virtualFile ?: return null
        return com.intellij.openapi.module.ModuleUtilCore.findModuleForFile(file, literal.project)
    }

    /** The jpm module whose manifest this is, as jpm last reported it. */
    private fun jpmModule(literal: JsonStringLiteral): JpmModuleInfo? {
        val parent = literal.containingFile.originalFile.virtualFile?.parent ?: return null
        val dir = parent.fileSystem.getNioPath(parent) ?: return null
        val infos = literal.project.getService(JpmSync::class.java)?.infos ?: return null
        return infos.asSequence().flatMap { it.modules }.firstOrNull { runCatching { java.nio.file.Path.of(it.dir).toRealPath() == dir.toRealPath() }.getOrDefault(false) }
    }

    private fun directory(literal: JsonStringLiteral, file: com.intellij.openapi.vfs.VirtualFile?): PsiElement? =
        file?.let { com.intellij.psi.PsiManager.getInstance(literal.project).findDirectory(it) }

    /** The JDK the module builds with: its home folder. */
    class JdkReference(literal: JsonStringLiteral) : PsiReferenceBase<JsonStringLiteral>(literal, range(literal), true) {
        override fun resolve(): PsiElement? {
            val home = jpmModule(element)?.javaHome?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(java.nio.file.Path.of(it)) }
                ?: module(element)?.let { com.intellij.openapi.roots.ModuleRootManager.getInstance(it).sdk?.homeDirectory }
            return directory(element, home)
        }

        override fun handleElementRename(newElementName: String): PsiElement = element
    }

    /** The resource files a `filter` glob matches, in the module's resource folders. */
    class FilterReference(literal: JsonStringLiteral) : PsiReferenceBase.Poly<JsonStringLiteral>(literal, range(literal), true) {
        override fun multiResolve(incompleteCode: Boolean): Array<com.intellij.psi.ResolveResult> {
            val matcher = runCatching { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + element.value) }.getOrNull()
                ?: return com.intellij.psi.ResolveResult.EMPTY_ARRAY
            val psi = com.intellij.psi.PsiManager.getInstance(element.project)
            val out = ArrayList<com.intellij.psi.ResolveResult>()
            for (root in resourceDirs(element)) {
                com.intellij.openapi.vfs.VfsUtilCore.iterateChildrenRecursively(root, null) { f ->
                    val rel = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(f, root)
                    if (!f.isDirectory && rel != null && matcher.matches(java.nio.file.Path.of(rel))) psi.findFile(f)?.let { out.add(com.intellij.psi.PsiElementResolveResult(it)) }
                    true
                }
            }
            return out.toTypedArray()
        }

        override fun handleElementRename(newElementName: String): PsiElement = element

        private fun resourceDirs(literal: JsonStringLiteral): List<com.intellij.openapi.vfs.VirtualFile> {
            val dir = literal.containingFile.originalFile.virtualFile?.parent ?: return emptyList()
            val reported = jpmModule(literal)?.resourceDir?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(java.nio.file.Path.of(it)) }
            if (reported != null) return listOf(reported)
            return listOf("src/assets", "src/main/resources").mapNotNull { dir.findFileByRelativePath(it) }
        }
    }

    /** The library jpm made for a dependency (its jar, under External Libraries), or the workspace module it names. */
    class LibraryReference(literal: JsonStringLiteral) : PsiReferenceBase<JsonStringLiteral>(literal, range(literal), true) {
        override fun resolve(): PsiElement? {
            val key = element.value
            if (key.startsWith("workspace:")) return directory(element, workspaceModuleDir(element, key.removePrefix("workspace:")))
            return directory(element, library(element, key)?.getFiles(com.intellij.openapi.roots.OrderRootType.CLASSES)?.firstOrNull())
        }

        override fun handleElementRename(newElementName: String): PsiElement = element
    }

    /** The jpm library for a dependency key: named "jpm: <key>:<version>" (see JpmProjectSetup). */
    fun library(literal: JsonStringLiteral, key: String): com.intellij.openapi.roots.libraries.Library? {
        val prefix = "jpm: $key:"
        fun matches(name: String?) = name != null && (name.startsWith(prefix) && !name.substring(prefix.length).contains(':') || name == "jpm: $key")
        com.intellij.openapi.roots.libraries.LibraryTablesRegistrar.getInstance().getLibraryTable(literal.project).libraries.firstOrNull { matches(it.name) }?.let { return it }
        val modules = module(literal)?.let { listOf(it) }.orEmpty() + com.intellij.openapi.module.ModuleManager.getInstance(literal.project).modules
        for (m in modules) {
            var found: com.intellij.openapi.roots.libraries.Library? = null
            com.intellij.openapi.roots.OrderEnumerator.orderEntries(m).forEachLibrary { if (matches(it.name)) { found = it; false } else true }
            if (found != null) return found
        }
        return null
    }

    private fun workspaceModuleDir(literal: JsonStringLiteral, name: String): com.intellij.openapi.vfs.VirtualFile? {
        val infos = literal.project.getService(JpmSync::class.java)?.infos.orEmpty()
        infos.asSequence().flatMap { it.modules }.firstOrNull { it.name == name }
            ?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(java.nio.file.Path.of(it.dir)) }?.let { return it }
        var dir = literal.containingFile.originalFile.virtualFile?.parent
        while (dir != null) {
            val manifest = dir.findChild(Jpm.MANIFEST)
            if (manifest != null) {
                val psi = com.intellij.psi.PsiManager.getInstance(literal.project).findFile(manifest) as? JsonFile
                val workspace = (psi?.topLevelValue as? JsonObject)?.findProperty("workspace")?.value as? JsonArray
                for (entry in workspace?.valueList.orEmpty()) {
                    val sub = (entry as? JsonStringLiteral)?.value?.let { dir!!.findFileByRelativePath(it) } ?: continue
                    val subManifest = sub.findChild(Jpm.MANIFEST)?.let { com.intellij.psi.PsiManager.getInstance(literal.project).findFile(it) } as? JsonFile
                    val subName = ((subManifest?.topLevelValue as? JsonObject)?.findProperty("name")?.value as? JsonStringLiteral)?.value
                    if (subName == name || subName == null && sub.name == name) return sub
                }
            }
            dir = dir.parent
        }
        return null
    }

    /** Where a dependency declares the define a `define` entry for it sets: the define in the dependency's own project.jsonc. */
    class DependencyDefineReference(literal: JsonStringLiteral, private val key: String, private val name: String) :
        PsiReferenceBase<JsonStringLiteral>(literal, range(literal), true) {
        override fun resolve(): PsiElement? {
            val manifest = dependencyManifest(element, key) ?: return null
            val defines = (manifest.topLevelValue as? JsonObject)?.findProperty("define")?.value as? JsonObject ?: return null
            return defines.findProperty(name)
        }

        override fun handleElementRename(newElementName: String): PsiElement = element
    }

    /** The project.jsonc of a source dependency: a workspace module, or a Git dependency's checkout (found from its library's sources). */
    fun dependencyManifest(literal: JsonStringLiteral, key: String): JsonFile? {
        val psi = com.intellij.psi.PsiManager.getInstance(literal.project)
        val dir = if (key.startsWith("workspace:")) workspaceModuleDir(literal, key.removePrefix("workspace:")) else {
            val lib = library(literal, key)
            val roots = lib?.getFiles(com.intellij.openapi.roots.OrderRootType.SOURCES).orEmpty().toList()
            roots.asSequence().map { com.intellij.openapi.vfs.VfsUtilCore.getVirtualFileForJar(it) ?: it }
                .flatMap { generateSequence(it) { f -> f.parent }.take(4) }
                .firstOrNull { it.isDirectory && it.findChild(Jpm.MANIFEST) != null }
        }
        return dir?.findChild(Jpm.MANIFEST)?.let { psi.findFile(it) } as? JsonFile
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
