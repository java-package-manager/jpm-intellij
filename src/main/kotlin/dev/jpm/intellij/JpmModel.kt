package dev.jpm.intellij

import com.google.gson.Gson

/** What `jpm info --json --ide` reports (infoVersion 1). Fields are only ever added. */
class JpmProjectInfo {
    var infoVersion: Int = 0
    var jpmVersion: String? = null
    var projectDir: String = ""
    var name: String = ""
    var version: String? = null
    var workspace: Boolean = false
    var scripts: List<String>? = null
    /** Settings of jpm extensions by name, as in the root project.jsonc (e.g. "publish"). */
    var extensions: Map<String, Any?>? = null
    var modules: List<JpmModuleInfo> = emptyList()

    companion object {
        fun parse(json: String): JpmProjectInfo {
            val start = json.indexOf('{')
            require(start >= 0) { "jpm info printed no JSON" }
            return Gson().fromJson(json.substring(start), JpmProjectInfo::class.java)
        }
    }
}

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
    var extensions: Map<String, Any?>? = null

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
