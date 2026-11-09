# Correctness

Bugs, races, unhandled edge cases, and tests that pass when they shouldn't.

**When planning:** take the rules below into account when designing the change.

**When reviewing:** report a finding when the diff matches a rule's `Flag:` line and no `Not when:` applies, or when it has a clear problem in this lens's area that no rule covers (mark it `no rule`). Cite the rule ID, location, and the failing input or interleaving.
