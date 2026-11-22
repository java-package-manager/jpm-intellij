# AGENTS.md

Facts for AI agents working on jpm-intellij. Reasons are in [CONTRIBUTING.md](CONTRIBUTING.md) and
[docs/adr](docs/adr).

## Commands

| What | Command |
|---|---|
| Tests and plugin zip | `./gradlew test buildPlugin` |
| Run an IDE with the plugin | `./gradlew runIde` |
| Compatibility check | `./gradlew verifyPlugin` |
| Comment lint | `java checks/Comments.java check src` |

## Where things live

- `src/main/kotlin/dev/jpm/intellij/`: `Jpm.kt` runs jpm, `JpmModel.kt` reads its output, `JpmSync.kt` and
  `JpmProjectSetup.kt` load a project into the IDE, `JpmToolWindow.kt` and `JpmTasks.kt` are the tool
  window, `JpmMigration.kt` imports Maven and Gradle builds, the wizards create projects.
- `src/main/resources/META-INF/plugin.xml`: extension points. `src/test/kotlin`: tests.

## Rules that must not be broken

- No `//` or `/* */` comments (decisions go into `docs/adr`).
- Learn about projects only through jpm; never bundle jpm.
- Keep `sinceBuild` unless asked: jpm's client jar targets its Java version.
- Do not push, tag, or file issues without being asked.

## Done means

`./gradlew test` and the comment lint pass, `README.md` describes user-facing changes, and the commit
message says what changed and why.
