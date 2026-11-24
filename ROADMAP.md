# Roadmap

What comes next for jpm-intellij. jpm's own plan is in its
[ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md); the language server and the
formatter are in jpm-format's [ROADMAP.md](https://github.com/java-package-manager/jpm-format/blob/main/ROADMAP.md).

Status: `[ ]` open, `[~]` in progress, `[x]` done.

## Migration UI

Migrating a project used to show only the output of `jpm import` in a plain text box.

- [x] Show the generated `project.jsonc` in an editor with syntax highlighting, and let the user edit
      it before it is written.
- [x] Show warnings prominently, above or next to the manifest rather than at the bottom of the
      output, so they are reviewed before migrating.
- [x] Offer to switch from the Maven layout to the jpm layout: move the source, resource and test
      folders, and drop `"layout": "maven"` from the manifest.
- [x] Offer to delete what is left of the old build: Gradle files and folders (`build.gradle(.kts)`,
      `settings.gradle(.kts)`, `gradle/`, `gradlew`, `gradlew.bat`, `gradle.properties`), and `pom.xml`
      and the Maven wrapper for Maven projects.
- [x] Scan the whole project and flag everything that still uses Gradle or Maven, for example CI
      workflows, Dockerfiles, scripts that call `./gradlew` or `mvn`, Renovate or Dependabot settings,
      `.gitignore` entries and IDE run configurations.

## Editing project.jsonc

- [x] Ctrl+click on values in `project.jsonc` goes to what they name:
  - `main`, `defineClass` and `scripts`: the class (or the script file);
  - dependencies and `exclude`: the library under External Libraries in the project view;
  - `relocate`: the package in the project view;
  - `license`: the license file (`LICENSE`, `LICENSE.md`, `LICENSE.txt`, `LICENCE`, `COPYING` and the
    other common spellings);
  - `filter`: the file;
  - `workspace`, `sources` and `resources`: the folder in the project view;
  - `patches`: the patch file;
  - `hooks`: the script the hook runs;
  - `java`: the JDK;
  - values in `define` for a dependency: the define where that dependency declares it.
- [x] Every value that names a file or folder gives a warning when it does not exist: `patches`, script
      files, `sources`, `resources` and workspace modules. `filter` globs that match no resource are a warning
      of jpm check's `paths` check, shown with the other check results.
- [x] Renames update the manifest: renaming a class updates `main` and `defineClass`, moving a package
      updates `relocate`, renaming a script updates the `hooks` that run it, moving a file or folder updates
      the paths that name it.
- [x] Completion of dependency coordinates and versions, and the resolved version from `project.lock`
      shown next to each dependency (inlay hint). Versions come from jpm's `dependencies/versions` (protocol
      1.1), `group:artifact` coordinates from `dependencies/search` (protocol 1.2: jpm's cache, then Maven
      Central's search).
- [x] An inspection for dependencies with a newer version, with a quick fix that runs `jpm update`.
- [x] Documentation from the schema on hover, for every field.
- [x] jpm's warnings and errors in `project.jsonc`, at the value they are about (`jpm check` over the
      protocol, on save).

## Formatting

- [ ] Run `jpm format` on save.

## More

- [x] Run configurations generated from `main` entries and `scripts`.
- [x] A dependency graph view, using what `jpm why` knows.
- [x] New projects ignore jpm's folders (`dist/`, `.cache/`, `.env.local`) in Git: the wizard runs
      `jpm init`, which adds the missing entries to a `.gitignore` that exists already.
