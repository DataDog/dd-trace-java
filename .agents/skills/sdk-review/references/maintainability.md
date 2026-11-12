# Maintainability

Duplication, dead code, comments, naming, and test value.

The [techdebt skill](../../techdebt/SKILL.md) is a separate, refactor-only review of duplication, unnecessary complexity, and dead code. This lens does not replace it or reuse its output. Keep the two distinct:

- This lens reports findings against the frozen diff and does not offer or apply fixes. `techdebt` offers behavior-preserving fixes.
- Findings here use this skill's severity scale and rule IDs. `techdebt` has its own report format.
- This lens also covers comments, naming, and test value, which `techdebt` does not.
- Do not run `techdebt` from this lens. Where both would flag the same code, that overlap is expected.

**When planning:** take the rules below into account when designing the change.

**When reviewing:** report a finding when the diff matches a rule's `Flag:` line and no `Not when:` applies, or when it has a clear problem in this lens's area that no rule covers (mark it `no rule`). Cite the rule ID and point to the existing helper or simpler shape.
