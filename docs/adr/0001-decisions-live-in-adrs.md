# 0001. Decisions live in ADRs, not in code comments

Status: accepted

The same policy as jpm's ADR 0001: the Kotlin sources have no `//` or `/* */` comments, a non-obvious
decision goes into an ADR here, and KDoc is not a comment. `java checks/Comments.java check src` is the
lint rule (CI runs it), with its baseline in `checks/comments-baseline.txt`, which may only go down.
