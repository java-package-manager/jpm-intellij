# jpm for IntelliJ IDEA

Opens [jpm](https://github.com/java-package-manager/jpm) projects in IntelliJ IDEA (2026.1 and newer) and keeps them set
up: open a folder with a `project.jsonc` and it is ready to code in, with nothing to configure.

## What it does

- **Project setup.** One module per jpm module (and one for a workspace root), with `src/` as sources,
  `src/assets/` as resources, `test/` and `test/assets/` as test sources and resources, and `.cache/` and
  `dist/` excluded. Generated sources (the `BuildConfig` class from defines) are added too, so they resolve
  without a build.
- **Dependencies with sources.** Every package jpm resolved becomes a library with its sources jar, in the
  scope it is needed in: runtime dependencies as compile, `dev` as provided, `runtimeOnly` as runtime,
  `test` as test. Workspace modules depend on each other as modules.
- **JDK, automatically.** Each module gets the JDK jpm builds it with, registered in IntelliJ when it is not
  yet, and its language level from `"target"`. A JDK the project asks for that is not installed is
  installed by jpm, as on the command line.
- **Editing `project.jsonc`.** Completion, documentation and validation from the JSON Schema of the jpm you
  have installed, so the editor knows exactly the fields that jpm reads, offline and without a `"$schema"`
  line: unknown fields, wrong dependency keys, misspelled options and invalid values are underlined as you
  type. Ctrl+click on a value goes to what it names: the class of `main` and `defineClass`, the folders of
  `workspace`, `sources` and `resources`, patch and script files, the script a hook runs, the packages
  `relocate` moves, and the license file. Renaming or moving those updates `project.jsonc`. A value that names a
  file or folder that does not exist (workspace modules, `sources`, `resources`, patches, script files) gets a
  warning.
- **Nested projects.** Every jpm project in the opened folder is loaded: the folder's own, projects nested
  inside it (test fixtures, examples), and projects in subfolders of a folder that is not one (a monorepo, a
  backend next to a frontend). A workspace's modules load with their workspace. Each project gets its own
  section in the tool window, and its commands run in its folder. Hidden folders and build output are not
  searched. A nested project that fails to load (a deliberately broken fixture, say) keeps its last loaded
  state and is reported, without holding up the others.
- **Automatic reload.** Changes to `project.jsonc` or `project.lock` (from the editor, `jpm add`, a `git
  pull`) reload the project, and IntelliJ indexes what changed. **Tools | Reload jpm Project** reloads by
  hand.
- **A jpm tool window** on the right, like Gradle's and Maven's, with the commands that can actually run:
  build, clean, release builds and the dependency commands always; test when there are tests; start and
  dev (one per entry point) and native builds when there is a `main`; bundles always (flat jars for modules
  without a `main`, such as plugins); publish when
  `extensions.publish` names repositories (one per repository), and `publish --local` always; and your
  scripts. In a workspace each module also gets its own tasks, run in its folder so jpm acts on that module
  only, next to its dependencies. Double-click to run; output goes to the Run window, where the command can
  be rerun or stopped. Commands can also be saved as **jpm** run configurations.
- **Run configurations** for every `main` entry (`jpm start`, `jpm start <entry>`) and every script
  (`jpm run <script>`), named after their module in a workspace, and kept in step with `project.jsonc` on
  every reload. Edit one and it becomes yours: it is no longer updated or removed. Settings can turn them off.
- **Maven and Gradle migration.** Opening a Maven or Gradle project offers to move it to jpm (also
  **Tools | Migrate to jpm…**). The dialog lists what does not carry over first, then shows each
  `project.jsonc` that `jpm import` would write in an editor, highlighted, to change before it is written.
  It can also move the sources from the Maven layout to jpm's (`src/`, `src/assets/`, `test/`, `test/assets/`)
  and drop `"layout": "maven"`, and delete the old build (build files, wrappers, `gradle/`, `build/` and
  `target/`); by default the Maven or Gradle files are not touched. Afterwards it lists what still uses
  Gradle or Maven, such as CI workflows, Dockerfiles, scripts, Renovate or Dependabot settings, `.gitignore`
  entries and run configurations; double-click one to open it. See
  [MIGRATION.md](https://github.com/java-package-manager/jpm/blob/main/MIGRATION.md).
- **New projects and modules.** jpm is a build system in the Java wizard, next to IntelliJ, Maven and
  Gradle (**File | New | Project** or **File | New | Module**, Java, Build system: jpm), and **File | New |
  Project | jpm** is there too; both run `jpm init`, and there is no JDK to pick. A new module inside a jpm
  workspace is added to the workspace's `"workspace"` list (comments and layout of `project.jsonc` are kept);
  elsewhere it becomes a jpm project of its own, which the plugin loads as a nested project.

## Installing

Add the plugin's update site once, and IntelliJ installs it and keeps it up to date like any other plugin:
**Settings | Plugins | ⚙ | Manage Plugin Repositories… | +**, then

```
https://github.com/java-package-manager/jpm-intellij/releases/latest/download/updatePlugins.xml
```

and search for **jpm** on the Marketplace tab. Each release's zip can also be installed by hand with
**Settings | Plugins | ⚙ | Install Plugin from Disk…**.

## Requirements

jpm 0.12.0 or newer (for the jpm protocol, `jpm api`), installed in `~/.jpm/bin` by the jpm install script or
on `PATH`. Without jpm, the plugin offers to install it.
Another location can be set in **Settings | Build, Execution, Deployment | Build Tools | jpm**, which also
turns automatic reload off.

## How it works

The plugin starts `jpm api` and asks it for the project over the jpm protocol, with jpm's client
(`dev.jpm:jpm-api`), then applies what jpm reports. jpm stays the
source of truth: the plugin never reads `project.jsonc` itself, so it understands every manifest jpm does.
IntelliJ's own build (Build | Build Project, running classes and tests from the gutter, debugging) works on
the configured modules and writes to `.cache/idea/`, apart from jpm's output.

## The schema

The plugin does not bundle jpm's schema. It asks jpm for the schema built into it (`schema/manifest`), keeps
it in the IDE's system folder, and serves that file for every `project.jsonc` (also for manifests whose
`"$schema"` names jpm's published schema). jpm is asked again when
the project reloads after jpm was upgraded, or when its path changes in the settings.

## Building

```sh
./gradlew test buildPlugin     # build/distributions/jpm-intellij-<version>.zip
./gradlew runIde               # an IDE with the plugin installed
```

Install the zip with **Settings | Plugins | ⚙ | Install Plugin from Disk…**.

## Releasing

Change `pluginVersion` in `gradle.properties` and push to `main`: a version that has no release yet is
tagged (plain, no `v` prefix) and released by that run; there is no tag to push by hand. A version is
never released twice, and versions like `1.0.0-rc1` become GitHub prereleases.

CI tests and verifies the plugin, then publishes a GitHub release with the zip, `SHA256SUMS` and the
`updatePlugins.xml` that installed IDEs update from. With a `JETBRAINS_MARKETPLACE_TOKEN` secret it also
publishes to the JetBrains Marketplace (the first upload has to be made by hand on the Marketplace), signed
when `PLUGIN_SIGNING_CERTIFICATE_CHAIN`, `PLUGIN_SIGNING_PRIVATE_KEY` and
`PLUGIN_SIGNING_PRIVATE_KEY_PASSWORD` are set.

## License

MIT, see [LICENSE](LICENSE).
