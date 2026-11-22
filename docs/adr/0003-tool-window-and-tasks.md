# 0003. The jpm tool window and its tasks

Status: accepted

## Decision

- Several jpm projects in nested folders get one section each in the tool window, and each section's
  commands run in its project's folder (`JpmToolWindow`).
- Only what can run is shown: no test without tests, no start or dev without a main, no publish without
  repositories.
- A module's own tasks run in its folder, so jpm acts on that module only.
- `jpm start` from the root needs one module to run: the project's, or the only one with a main
  (`JpmTasks`).
