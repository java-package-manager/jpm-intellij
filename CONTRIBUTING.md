# Contributing to jpm-intellij

jpm-intellij is the IntelliJ IDEA plugin for jpm. [AGENTS.md](AGENTS.md) has the same facts for AI agents;
the plan is jpm's [ROADMAP.md](https://github.com/java-package-manager/jpm/blob/main/ROADMAP.md) (Milestone 4
moves the plugin to jpm's client jar, Milestone 5 adds the migration UI and `project.jsonc` editing).

## Build, test, run

It needs a JDK 21 and, for most tests, jpm on PATH.

```sh
./gradlew test buildPlugin     # build/distributions/jpm-intellij-<version>.zip
./gradlew runIde               # an IDE with the plugin installed
./gradlew verifyPlugin         # compatibility with IntelliJ IDEA releases
java checks/Comments.java check src     # the comment lint CI runs
```

## Rules for the code

- No `//` or `/* */` comments ([docs/adr/0001](docs/adr/0001-decisions-live-in-adrs.md)). Decisions go
  into [docs/adr](docs/adr); KDoc is fine.
- The plugin learns about a project only from jpm (`jpm info --json --ide` today, jpm's client jar once
  it exists). It never parses `project.jsonc` or build files itself, and never bundles a jpm.
- The oldest supported IDE is `sinceBuild` in `build.gradle.kts` (261, on Java 21). jpm's client jar
  targets that IDE's Java.
- No stability before 1.0.

## Workflow and releases

- One local branch per change; `./gradlew test` passes before it is merged into `main`; `main` is pushed
  once.
- Change `pluginVersion` in `gradle.properties` and push to `main`: CI tags the commit, releases it on
  GitHub, and publishes it to the JetBrains Marketplace when a token is set.
