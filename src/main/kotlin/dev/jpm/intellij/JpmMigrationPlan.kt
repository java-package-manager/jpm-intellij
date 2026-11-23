package dev.jpm.intellij

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * What a migration to jpm does to the files, apart from the IDE: reading `jpm import --dry-run`, switching a module
 * from the Maven layout to jpm's, deleting the old build, and finding what still uses Gradle or Maven.
 */
object JpmMigrationPlan {
    /** What `jpm import --dry-run` printed: each manifest by its folder relative to the project ("" for the root), and the warnings. */
    class Preview(val manifests: LinkedHashMap<String, String>, val warnings: List<String>)

    fun parse(stdout: String, stderr: String): Preview {
        val manifests = LinkedHashMap<String, String>()
        var current: String? = null
        val text = StringBuilder()
        fun flush() {
            current?.let { manifests[it] = text.toString().trim() + "\n" }
            text.setLength(0)
        }
        val header = Regex("^// (?:(.+)/)?${Regex.escape(Jpm.MANIFEST)}$")
        for (line in strip(stdout).lines()) {
            val m = header.find(line.trim())
            if (m != null) {
                flush()
                current = m.groupValues[1]
                continue
            }
            if (current != null) text.append(line).append('\n')
        }
        flush()
        val warnings = strip(stderr).lines().map { it.trim() }.filter { it.startsWith("warning:") }.map { it.removePrefix("warning:").trim() }
        return Preview(manifests, warnings)
    }

    private fun strip(s: String) = s.lines().filterNot { it.startsWith("Picked up ") }.joinToString("\n").replace(Regex("\u001b\\[[0-9;]*m"), "")

    /** Whether a manifest's text asks for the Maven layout. */
    fun usesMavenLayout(manifest: String) = Regex("\"layout\"\\s*:\\s*\"(maven|gradle)\"").containsMatchIn(manifest)

    /** A manifest's text without its `"layout"` entry, so the module uses jpm's layout. */
    fun withoutLayout(manifest: String): String =
        manifest.replace(Regex("(?m)^[ \\t]*\"layout\"\\s*:\\s*\"(maven|gradle)\"\\s*,?[ \\t]*\\r?\\n"), "")
            .replace(Regex(",\\s*\"layout\"\\s*:\\s*\"(maven|gradle)\""), "")
            .replace(Regex("\"layout\"\\s*:\\s*\"(maven|gradle)\"\\s*,\\s*"), "")

    /** Where each Maven folder of a module goes in jpm's layout: sources to src/, resources to src/assets/, tests to test/. */
    private val moves = listOf(
        "src/main/java" to "src", "src/main/kotlin" to "src", "src/main/groovy" to "src", "src/main/scala" to "src",
        "src/main/resources" to "src/assets",
        "src/test/java" to "test", "src/test/kotlin" to "test", "src/test/groovy" to "test", "src/test/scala" to "test",
        "src/test/resources" to "test/assets",
    )

    /**
     * Moves a module's Maven folders into jpm's layout and returns the files moved. Nothing is moved when a file would
     * land where one is already, or when the module has folders the switch would not know where to put; the reason
     * comes back as the exception's message.
     */
    fun switchLayout(module: Path): List<Path> {
        val plan = ArrayList<Pair<Path, Path>>()
        for ((from, to) in moves) {
            val src = module.resolve(from)
            if (!src.isDirectory()) continue
            Files.walk(src).use { s -> s.filter { it.isRegularFile() }.forEach { plan.add(it to module.resolve(to).resolve(src.relativize(it))) } }
        }
        val known = moves.map { it.first }.toSet()
        for (side in listOf("src/main", "src/test")) {
            val d = module.resolve(side)
            if (!d.isDirectory()) continue
            Files.list(d).use { s ->
                s.forEach { c -> if ("$side/${c.name}" !in known) throw IllegalStateException("${module.relativize(c)} has no place in jpm's layout") }
            }
        }
        val targets = HashSet<Path>()
        for ((src, dst) in plan) {
            if (!targets.add(dst)) throw IllegalStateException("${module.relativize(src)} and another file would both become ${module.relativize(dst)}")
            if (Files.exists(dst) && !plan.any { it.first == dst }) throw IllegalStateException("${module.relativize(dst)} exists already")
        }
        val tmp = Files.createTempDirectory(module, ".jpm-layout")
        val staged = plan.map { (src, dst) ->
            val t = tmp.resolve(module.relativize(dst))
            Files.createDirectories(t.parent)
            Files.move(src, t)
            t to dst
        }
        for (side in listOf("src/main", "src/test")) deleteEmpty(module.resolve(side))
        for ((t, dst) in staged) {
            Files.createDirectories(dst.parent)
            Files.move(t, dst, StandardCopyOption.REPLACE_EXISTING)
        }
        deleteEmpty(tmp)
        return staged.map { it.second }
    }

    private fun deleteEmpty(dir: Path) {
        if (!dir.isDirectory()) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { if (it.isDirectory() && Files.list(it).use { l -> !l.findAny().isPresent }) Files.delete(it) } }
    }

    private val gradleFiles = listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties",
        "gradlew", "gradlew.bat", "gradle", ".gradle", "build")
    private val mavenFiles = listOf("pom.xml", "mvnw", "mvnw.cmd", ".mvn", "target")

    /** The old build's files and folders in a project and its modules, which a migrated project no longer needs. */
    fun oldBuild(root: Path, modules: Collection<Path>): List<Path> {
        val out = LinkedHashSet<Path>()
        for (dir in listOf(root) + modules) {
            for (n in gradleFiles + mavenFiles) {
                val p = dir.resolve(n)
                if (Files.exists(p) && (n != "build" && n != "target" || dir.resolve("build.gradle").isRegularFile()
                        || dir.resolve("build.gradle.kts").isRegularFile() || n == "target" && dir.resolve("pom.xml").isRegularFile())) {
                    out.add(p)
                }
            }
        }
        return out.toList()
    }

    fun delete(paths: List<Path>) {
        for (p in paths) {
            if (p.isDirectory()) Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) } else Files.deleteIfExists(p)
        }
    }

    /** Something that still uses Gradle or Maven: a file, the line (from 1, or 0 for the whole file) and why. */
    data class Leftover(val file: Path, val line: Int, val what: String)

    private val skipDirs = setOf(".git", ".idea", ".cache", "dist", "node_modules", "build", "target", ".gradle", "out")
    private val tool = Regex("(\\./)?gradlew\\b|\\bgradle\\s+\\w|\\bmvnw?\\b|\\bmaven\\b|setup-gradle|gradle/actions|actions/setup-java.*cache:\\s*(maven|gradle)",
        RegexOption.IGNORE_CASE)

    /** Everything under [root] that still uses Gradle or Maven, in CI, Docker, scripts, update bots, .gitignore and run configurations. */
    fun leftovers(root: Path): List<Leftover> {
        val out = ArrayList<Leftover>()
        fun visit(dir: Path) {
            val children = Files.list(dir).use { s -> s.sorted().toList() }
            for (c in children) {
                val rel = root.relativize(c).toString().replace('\\', '/')
                if (c.isDirectory()) {
                    if (c.name !in skipDirs) visit(c)
                    continue
                }
                val kind = kind(rel, c.name) ?: continue
                val text = try {
                    Files.readString(c)
                } catch (e: Exception) {
                    continue
                }
                text.lines().forEachIndexed { i, l ->
                    val hit = when (kind) {
                        "gitignore" -> l.trim() in setOf("build/", "/build", "build", ".gradle", ".gradle/", "target/", "/target", "target")
                        "bot" -> Regex("gradle|maven", RegexOption.IGNORE_CASE).containsMatchIn(l)
                        "run" -> Regex("GradleRunConfiguration|MavenRunConfiguration|ExternalSystemSettings").containsMatchIn(l)
                        else -> tool.containsMatchIn(l)
                    }
                    if (hit) out.add(Leftover(c, i + 1, describe(kind, l.trim())))
                }
            }
        }
        visit(root)
        val idea = root.resolve(".idea/runConfigurations")
        if (idea.isDirectory()) {
            Files.list(idea).use { s ->
                s.sorted().forEach { f ->
                    val text = Files.readString(f)
                    if (Regex("GradleRunConfiguration|MavenRunConfiguration").containsMatchIn(text)) out.add(Leftover(f, 0, "IDE run configuration for Gradle or Maven"))
                }
            }
        }
        return out.distinct()
    }

    private fun kind(rel: String, name: String): String? = when {
        name == ".gitignore" -> "gitignore"
        name == "renovate.json" || name == "renovate.json5" || name == ".renovaterc" || name == ".renovaterc.json" || rel == ".github/dependabot.yml" -> "bot"
        rel.startsWith(".run/") && name.endsWith(".xml") -> "run"
        rel.startsWith(".github/workflows/") || rel == ".gitlab-ci.yml" || rel.startsWith(".circleci/") || name == "Jenkinsfile" || name == "azure-pipelines.yml" -> "ci"
        name.startsWith("Dockerfile") || name.endsWith(".dockerfile") || name == "docker-compose.yml" || name == "compose.yaml" -> "docker"
        name.endsWith(".sh") || name.endsWith(".bat") || name.endsWith(".cmd") || name.endsWith(".ps1") || name == "Makefile" || name == "justfile" -> "script"
        else -> null
    }

    private fun describe(kind: String, line: String) = when (kind) {
        "gitignore" -> "ignores Gradle or Maven output ($line); jpm writes to dist/ and .cache/"
        "bot" -> "dependency updates for Gradle or Maven ($line)"
        "run" -> "Gradle or Maven run configuration"
        "ci" -> "CI runs Gradle or Maven: $line"
        "docker" -> "Docker builds with Gradle or Maven: $line"
        else -> "script runs Gradle or Maven: $line"
    }
}
