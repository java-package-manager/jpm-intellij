package dev.jpm.intellij

/** A jpm project as the plugin uses it, made by [JpmApi] from jpm's protocol answers. */
class JpmProjectInfo {
    var jpmVersion: String? = null
    var projectDir: String = ""
    var name: String = ""
    var version: String? = null
    var workspace: Boolean = false
    var scripts: List<String>? = null
    var modules: List<JpmModuleInfo> = emptyList()
    /** The extensions this project can run: the ones it declares, then the global ones (jpm's extensions/list). */
    var extensionList: List<JpmExtensionInfo> = emptyList()
}

/** An extension and the commands its manifest describes. */
class JpmExtensionInfo(val name: String, val source: String, val version: String?, val description: String?,
                       val commands: List<JpmExtensionCommand>)

/** A command of an extension: [name] is what follows `jpm`, such as `publish` or `format check`. */
class JpmExtensionCommand(val name: String, val description: String?)

class JpmModuleInfo {
    var name: String = ""
    var group: String? = null
    var version: String? = null
    var dir: String = ""
    var relativeDir: String? = null
    var main: Map<String, String>? = null
    var layout: String? = null
    var sourceDir: String = ""
    var resourceDir: String = ""
    var testDir: String? = null
    var testResourceDir: String? = null
    var generatedSourceDir: String? = null
    var excludeDirs: List<String>? = null
    var scripts: List<String>? = null
    var java: String? = null
    var javaHome: String? = null
    var javaError: String? = null
    var release: Int? = null
    var dependencies: List<JpmDependencyInfo>? = null
    var ideDependencies: List<JpmIdeDependency>? = null
    /** Every package on the module's classpaths with the packages it depends on, for the dependency tree. */
    var graph: Map<String, JpmGraphNode>? = null

    /** Whether the module has test sources; worked out by the plugin after loading, not reported by jpm. */
    @Transient
    var hasTests: Boolean = false
}

/** A dependency as declared in project.jsonc. */
class JpmDependencyInfo {
    var key: String = ""
    var kind: String? = null
    var scope: String? = null
    var spec: String? = null
    var resolved: String? = null
    var module: String? = null
}

/** A package on the module's classpaths, with the narrowest scope it is needed in. */
class JpmIdeDependency {
    var id: String = ""
    /** compile, provided, runtime or test. */
    var scope: String = "compile"
    /** Set for workspace modules. */
    var module: String? = null
    var version: String? = null
    var jars: List<String>? = null
    var sources: String? = null
}

/** A package in a module's dependency graph: its version and the packages it depends on. */
class JpmGraphNode(val version: String?, val dependencies: List<String>)

/** A module's dependency tree, as `jpm why` and `gradle dependencies` show it. */
object JpmDependencyTree {
    /** A line of the tree: a package, its version, and whether it was shown above already (and is not expanded again). */
    class Line(val id: String, val version: String?, val repeated: Boolean, val children: List<Line>)

    /** The tree below one package; each package is expanded the first time it appears, and marked as repeated after that. */
    fun below(graph: Map<String, JpmGraphNode>, id: String, seen: MutableSet<String> = HashSet()): Line {
        val node = graph[id]
        if (!seen.add(id)) return Line(id, node?.version, true, emptyList())
        return Line(id, node?.version, false, node?.dependencies.orEmpty().map { below(graph, it, seen) })
    }
}
