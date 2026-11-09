# Codebase conventions

Repo-specific idioms: configuration, logging, shared helpers, test patterns, and build tooling. [AGENTS.md](../../../../AGENTS.md) also applies.

**When planning:** take the rules below into account when designing the change.

**When reviewing:** report a finding when the diff matches a rule's `Flag:` line and no `Not when:` applies, or when it has a clear problem in this lens's area that no rule covers (mark it `no rule`). Cite the rule ID and name the helper or pattern to use.
