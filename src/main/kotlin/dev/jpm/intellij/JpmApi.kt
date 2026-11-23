package dev.jpm.intellij

import dev.jpm.api.client.JpmClient
import dev.jpm.api.client.JpmError
import dev.jpm.api.client.JpmNotInstalled
import dev.jpm.api.client.LoadOptions
import dev.jpm.api.client.ProtocolMismatch
import dev.jpm.api.model.Classpaths
import dev.jpm.api.model.Module
import dev.jpm.api.model.Project
import java.nio.file.Path

/**
 * Talks to jpm over its protocol (`jpm api`) with jpm's client, dev.jpm:jpm-api, and turns its answers into the
 * plugin's model. A connection lives for one call: jpm reads the project afresh every time.
 */
object JpmApi {
    /** This plugin's version, from the plugin.xml the build stamps it into (the platform's lookups are internal API). */
    private val pluginVersion: String by lazy {
        val xml = JpmApi::class.java.getResourceAsStream("/META-INF/plugin.xml")?.use { String(it.readAllBytes(), Charsets.UTF_8) }.orEmpty()
        Regex("<version>\\s*([^<\\s]+)\\s*</version>").find(xml)?.groupValues?.get(1) ?: "unknown"
    }

    private fun <T> connect(dir: Path, block: (JpmClient) -> T): T {
        val exe = Jpm.executable() ?: throw JpmNotFoundException()
        val client = try {
            JpmClient.start(listOf(exe.toString(), "api"), dir, mapOf("JPM_COLOR" to "0"), "jpm-intellij", pluginVersion)
        } catch (e: JpmNotInstalled) {
            throw JpmNotFoundException()
        } catch (e: ProtocolMismatch) {
            throw JpmSync.LoadException(e.message ?: "jpm speaks another protocol version")
        } catch (e: JpmError) {
            throw JpmSync.LoadException("jpm api failed: " + e.message)
        }
        return client.use(block)
    }


    /** The project at [dir], with its classpaths, as the plugin's model. */
    fun load(dir: Path): JpmProjectInfo = connect(dir) { client ->
        val project = try {
            client.load(dir, LoadOptions.withSources())
        } catch (e: JpmError) {
            throw JpmSync.LoadException(describe(e))
        }
        val classpaths = try {
            client.classpaths(dir, emptyList())
        } catch (e: JpmError) {
            throw JpmSync.LoadException(describe(e))
        }
        val extensions = try {
            client.extensions(dir)
        } catch (e: JpmError) {
            throw JpmSync.LoadException(describe(e))
        }
        info(project, classpaths, client.jpmVersion()).apply { extensionList = extensions.map(::extension) }
    }

    fun extension(e: dev.jpm.api.model.Extension) = JpmExtensionInfo(e.name(), e.source(), e.version(), e.description(),
        e.commands().map { JpmExtensionCommand(it.name(), it.description()) })

    /**
     * The versions of these Maven dependencies, oldest first, and the newest release of each, from jpm's
     * `dependencies/versions` (protocol 1.1). Empty when this jpm does not have it.
     */
    @Suppress("UNCHECKED_CAST")
    fun versions(dir: Path, keys: List<String>): Map<String, JpmVersions.Known> = connect(dir) { client ->
        if (!client.supports("dependencies/versions") || keys.isEmpty()) return@connect emptyMap()
        val r = try {
            client.call("dependencies/versions", mapOf("dir" to dir.toAbsolutePath().toString(), "keys" to keys))
        } catch (e: JpmError) {
            return@connect emptyMap()
        }
        val out = LinkedHashMap<String, JpmVersions.Known>()
        (r["versions"] as? Map<String, Any?>)?.forEach { (k, v) ->
            val m = v as? Map<String, Any?> ?: return@forEach
            out[k] = JpmVersions.Known((m["versions"] as? List<Any?>).orEmpty().map { it.toString() }, m["latest"]?.toString())
        }
        out
    }

    /** jpm's quick checks of the project at [dir] (no compiling, no tests): its problems. */
    fun check(dir: Path, checks: List<String>): List<dev.jpm.api.model.Problem> = connect(dir) { client ->
        try {
            client.check(dir, checks, false).problems()
        } catch (e: JpmError) {
            val p = e.problem() ?: throw JpmSync.LoadException(describe(e))
            listOf(p)
        }
    }

    /** The JSON Schema of project.jsonc that the installed jpm reads, as JSON text. */
    fun manifestSchema(dir: Path): String = connect(dir) { client -> dev.jpm.api.json.Jsonc.write(client.manifestSchema()) }

    private fun describe(e: JpmError): String {
        val p = e.problem() ?: return e.message ?: "jpm could not load the project"
        val at = p.file()?.let { f -> " (" + f + (p.line()?.let { ":$it" } ?: "") + ")" } ?: ""
        return p.message() + at + (p.hint()?.let { "\n  hint: $it" } ?: "")
    }

    fun info(p: Project, classpaths: Map<String, Classpaths>, jpmVersion: String?): JpmProjectInfo = JpmProjectInfo().apply {
        this.jpmVersion = jpmVersion
        projectDir = p.dir().toString()
        name = p.name()
        version = p.version()
        workspace = p.workspace()
        scripts = p.scripts().map { it.name() }
        extensions = p.extensions()
        modules = p.modules().map { module(it, classpaths[it.name()]) }
    }

    private fun module(m: Module, cp: Classpaths?): JpmModuleInfo = JpmModuleInfo().apply {
        name = m.name()
        group = m.group()
        version = m.version()
        dir = m.dir().toString()
        relativeDir = m.relativeDir()
        main = m.main()
        layout = m.layout()
        val f = m.folders()
        sourceDir = f.sources().firstOrNull()?.toString().orEmpty()
        resourceDir = f.resources().firstOrNull()?.toString().orEmpty()
        testDir = f.tests().firstOrNull()?.toString()
        testResourceDir = f.testResources().firstOrNull()?.toString()
        generatedSourceDir = f.generatedSources().firstOrNull()?.toString()
        excludeDirs = f.exclude().map { it.toString() }
        scripts = m.scripts().map { it.name() }
        java = m.jdk().spec()
        javaHome = m.jdk().home()?.toString()
        javaError = m.jdk().error()
        release = m.jdk().release()
        extensions = m.extensions()
        dependencies = m.dependencies().map { d ->
            JpmDependencyInfo().apply {
                key = d.key()
                kind = d.kind()
                scope = d.scope()
                spec = d.spec()
                resolved = d.resolved()
                module = d.module()
            }
        }
        ideDependencies = cp?.let { ideDependencies(m, it) }.orEmpty()
        graph = cp?.let { graph(it) }.orEmpty()
    }

    /** Every package on the module's classpaths with its version and the packages it depends on. */
    fun graph(cp: Classpaths): Map<String, JpmGraphNode> {
        val out = LinkedHashMap<String, JpmGraphNode>()
        for (p in cp.compile() + cp.runtime() + cp.testCompile() + cp.testRuntime() + cp.processor()) {
            if (p.id() !in out) out[p.id()] = JpmGraphNode(p.version(), p.dependencies().orEmpty())
        }
        return out
    }

    /**
     * Every package on the module's classpaths with the narrowest scope it is needed in: on both the compile and
     * the runtime classpath it is compile, on the compile one only provided, on the runtime one only runtime,
     * otherwise test.
     */
    fun ideDependencies(m: Module, cp: Classpaths): List<JpmIdeDependency> {
        val compile = cp.compile().map { it.id() }.toSet()
        val runtime = cp.runtime().map { it.id() }.toSet()
        val sources = m.packages().associate { it.id() to it.sources() }
        val seen = LinkedHashMap<String, dev.jpm.api.model.Package>()
        for (p in cp.compile() + cp.runtime() + cp.testCompile() + cp.testRuntime()) seen.putIfAbsent(p.id(), p)
        return seen.values.mapNotNull { p ->
            val scope = when {
                p.id() in compile && p.id() in runtime -> "compile"
                p.id() in compile -> "provided"
                p.id() in runtime -> "runtime"
                else -> "test"
            }
            if (p.module() != null) {
                JpmIdeDependency().apply { id = p.id(); this.scope = scope; module = p.module() }
            } else if (p.jars().isEmpty()) {
                null
            } else {
                JpmIdeDependency().apply {
                    id = p.id()
                    this.scope = scope
                    version = p.version()
                    jars = p.jars().map { it.toString() }
                    this.sources = (p.sources() ?: sources[p.id()])?.toString()
                }
            }
        }
    }
}
