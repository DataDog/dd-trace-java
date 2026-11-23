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
  the `@NoEscape`, `@StaticLifetime` and `@BackgroundOnly` checks in `checks/contracts.md` belong
  here once their checkers land)*

## J2 Megamorphic dispatch (parked)

Parked for PR review since 2026-07-08. It is too in-the-weeds for most authors, and the rubric
should first earn trust with findings anyone can see in a profiler (allocation, unbounded memory,
regex). Revisit once the rubric has a track record.

**Mechanism.** A hot call site that sees three or more receiver types with no dominant one goes
megamorphic. The dispatch itself is cheap. The cost is that the call cannot be inlined, which
blocks optimization on both sides:
- caller side: the arguments escape into an opaque callee, so there is no scalar replacement for
  them, no constant propagation into the call, caller-saved registers are spilled across it, and
  nothing can be hoisted or reordered across it;
- callee side: the callee is never specialized for this caller, so argument types and constants
  that would have removed its branches and devirtualized its own calls stay invisible.

**When C2 still copes.** Two types stay bimorphic and inlinable. A dominant receiver (at least
`TypeProfileMajorReceiverPercent`, default 90%) still gets a guarded inline with an uncommon-trap
fallback, so a skewed site is usually fine. Watch for bimodal oscillation, where a recurring rare
type causes repeated deoptimization. The danger is a flat distribution over three or more types:
`TypeProfileWidth` is 2, the profile overflows, and the call becomes an itable call that is not
inlined.

**Fixes, cheapest first.** Keep the site to two types or fewer. Skip the fan-out in the common
case (for example, a listener array that is empty by default). Collapse several implementations
into one `final` class with an internal state switch, so every site is monomorphic. Split or
unroll a fan-out whose order is stable. Confirm with `-XX:+PrintInlining` (look for
`not inlined (megamorphic)`) or JITWatch, not by reading code.

**Why per-PR review misses it.** The worst megamorphic sites accumulate: each PR adds one type at
most, so a diff review only catches a PR that widens a site, never the sites that are already
bad. The heaviest known one is `Context.get` / `with` across the `Empty`, `Singleton` and
`Indexed` implementations, on the hottest path in the system. These need a periodic
`PrintInlining` census of the known hot sites, run separately from PR review.
