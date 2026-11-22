# 0002. Loading jpm projects into the IDE

Status: accepted

## Decision

- The plugin reads a project from `jpm info --json --ide`. jpm prints status lines to stderr; stdout holds
  only the JSON object (`JpmModel`). Moving to jpm's client jar replaces this (jpm roadmap, Milestone 4).
- The schema of `project.jsonc` comes from the jpm that loads the project, because another jpm may have
  another schema (`JpmSync`, `JpmSettings`).
- `jpm info` may write `project.lock`; that is part of the load, not a new change to react to (`JpmSync`).
- A `project.jsonc`, or a folder that may hold one, appearing, going or moving changes which projects
  there are (`JpmStartup`). `JpmFileListener` reloads a project when a manifest or the lockfile changes,
  for example after a run configuration ran jpm (`JpmRunConfiguration`).
- The project JDK and language level are those of the first module; jpm builds every module with its own
  (`JpmProjectSetup`).
- IntelliJ module names are jpm's. A name that another jpm project already uses is prefixed with that
  project's name.
- Modules jpm-intellij made earlier that are gone are removed, and so are modules IntelliJ made for these
  folders on its own.
- Workspace dependencies refer to modules of the same jpm project.
- IntelliJ's own builder writes elsewhere than jpm, so the two never trip over each other.
