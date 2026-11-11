# Roadmap

Not read during a review. Notes on where the rubric fits and what should move out of it.

## Where this fits

The rubric complements benchmark-based regression-blocking: benchmarks catch *measured*
regressions on *covered* ops; this catches *un-benchmarked* pattern-smells in *any* diff.

**Advisory, not blocking** initially. A nondeterministic, false-positive-prone check that blocks
merges dies of being ignored (false positives) or gives false confidence (false negatives). Earn
blocking only after precision is proven, and likely only on the deterministic-lint subset.

## Deterministic-lint candidates (DON'T spend AI budget — make these real lints)

Fixed-signature, mechanically checkable:
- per-call cache / regex / expensive-object creation that should be static/once (the
  `@StaticLifetime`-annotated subset is checked by #9 in `checks/contracts.md`; this line covers
  the still-unannotated general case)
- boxing in specific hot APIs
- using a string-API where an id-API exists on a hot decorator
- the existing convention rules (e.g. don't extract one-shot instrumentation methods to constants)
- *(grows as patterns prove mechanically checkable — migrate them off the AI as they stabilize;
  the `@NoEscape` and `@StaticLifetime` field-storage checks in `checks/contracts.md` belong here
  once their checkers land)*
