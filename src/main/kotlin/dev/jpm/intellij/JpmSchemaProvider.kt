package dev.jpm.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import java.nio.file.Files
import java.nio.file.Path

/**
 * Completion, documentation and validation for every project.jsonc, from the schema of the jpm that is
 * installed: `jpm schema --path` writes the schema built into that jpm to ~/.jpm/schemas/<version>/ and
 * prints where, so the editor describes exactly the manifests that jpm reads, offline. Manifests whose
 * "$schema" names jpm's published schema get the local one too, so nothing is downloaded. Without jpm (or
 * with one too old for `jpm schema`) the plugin offers no schema of its own.
 */
class JpmSchemaProviderFactory : JsonSchemaProviderFactory, DumbAware {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(JpmSchemaProvider)
}

object JpmSchemaProvider : JsonSchemaFileProvider {
    const val SCHEMA_URL = "https://github.com/java-package-manager/jpm/releases/latest/download/project.schema.json"

    private val log = logger<JpmSchemaProvider>()

    /** The schema file of the installed jpm, once known. */
    @Volatile
    private var schema: Path? = null

    /** Which jpm [schema] came from (its path and modification time), so an upgraded or another jpm is asked again. */
    @Volatile
    private var askedJpm: String? = null

    override fun isAvailable(file: VirtualFile): Boolean = file.name == Jpm.MANIFEST

    override fun getName(): String = "jpm project.jsonc"

    override fun getPresentableName(): String = "jpm"

    override fun getSchemaFile(): VirtualFile? = schema?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }

    override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema

    /** Stands in for jpm's published schema only when there is a local one; otherwise IntelliJ may download it. */
    override fun getRemoteSource(): String? = if (schema != null) SCHEMA_URL else null

    /**
     * Asks the jpm executable for its schema, unless that jpm was asked already. Runs jpm, so call it off
     * the EDT; failures leave no schema and are only logged.
     */
    fun update() {
        val exe = Jpm.executable()
        val key = exe?.let { "$it@" + try { Files.getLastModifiedTime(it).toMillis() } catch (e: java.io.IOException) { 0 } }
        if (key == askedJpm) return
        askedJpm = key
        if (exe == null) return use(null)
        val path = try {
            val out = Jpm.run(exe.parent ?: Path.of(System.getProperty("user.home")), listOf("schema", "--path"), null)
            if (out.exitCode == 0) parsePath(out.stdout)
            else null.also { log.info("jpm schema --path failed (a jpm without the schema command?): ${out.stderr.trim()}") }
        } catch (e: Exception) {
            log.info("Could not run jpm schema --path", e)
            null
        }
        use(path)
    }

    /** The schema file named by the output of `jpm schema --path`: its last line, if that file exists. */
    fun parsePath(stdout: String): Path? {
        val line = stdout.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        val p = try { Path.of(line) } catch (e: java.nio.file.InvalidPathException) { return null }
        return p.takeIf { it.isAbsolute && Files.isRegularFile(it) }
    }

    /** Serves [path] as the schema of project.jsonc (null for none) and has open projects pick it up. */
    fun use(path: Path?) {
        if (path != null) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (path == schema) return
        schema = path
        ApplicationManager.getApplication().invokeLater {
            for (p in ProjectManager.getInstance().openProjects) if (!p.isDisposed) JsonSchemaService.Impl.get(p).reset()
        }
    }

    /** Forgets which jpm was asked, so the next [update] asks again (after the jpm path setting changes). */
    fun forget() {
        askedJpm = null
    }
}
