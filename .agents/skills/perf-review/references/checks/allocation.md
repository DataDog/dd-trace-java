# Per-call and escaping allocation

Checks #1, #7, J1, J4, J16. SEV-2/3, can reach SEV-1 via tail latency.

The tracer shares the application heap. Additional allocation contributes to GC and raises
stop-the-world pauses — directly increasing tail latency for the customer's application. The JVM's
escape analysis eliminates *local* short-lived allocations, but only when the object stays local.
Stored in a map, returned, captured by a lambda, or passed to a non-inlined virtual call: it
escapes, and it's real.

## Checks

- **#1 — Per-span/per-call allocation on a hot path** — *per-span (or hotter) AND the object isn't
  trivially short-lived (it's retained, returned, captured, or passed across a boundary)* —
  flag-with-confidence if clearly per-span + retained; flag-as-measure if lifetime/escape is
  borderline — SEV-2/3 (→SEV-1 if unbounded) — fix: reuse, pool, dense/positional storage, defer
  out of the hot path.
- **#7 — Escape / allocation-elision defeated** *(Java/Go/.NET/V8 all have a version)* — *a
  refactor makes a previously-local object escape (stored, returned, captured by a closure, passed
  to a virtual/non-inlined call) → silent heap allocation on a hot path* — **flag-as-measure**
  ("may now escape and allocate; verify with an allocation profiler") — SEV-2/3 — fix: keep it
  local; avoid the escaping store/capture.
- **J1 — Escaping allocation defeats Escape Analysis** *(refines #1, #7)*. The JVM scalar-replaces
  only *non-escaping* short-lived objects. An object stored in the tag map / span / a collection,
  iterated at serialization, or passed to a virtual/megamorphic call **escapes** → EA can't elide
  it → real heap allocation. The trap: *"the JIT will scalar-replace it" is false for escaping
  objects* — the dense-store −48% came from removing exactly the escaping per-tag wrappers; an
  earlier no-Entry change measured ~0 because *those* entries were transient/EA-eligible. **Verify
  in JFR — EA'd objects don't appear in alloc profiles, so a surviving `Entry` in the profile
  *proves* it escapes.** Before flagging: if the diff already ships a JMH benchmark purpose-built
  to answer exactly this (e.g. its Javadoc says run with a GC profiler to confirm
  scalar-replacement) and that benchmark has been run, treat the question as answered — list it
  under Correctly suppressed / Checked, no issue instead of asking the reader to re-verify.
- **J16 — "Optimizing" a short-lived object defeats Escape Analysis** *(refines #7, J1)*. Two
  changes that look like wins make escape analysis keep an object it would otherwise have removed.
  Judge by the returned object's expected lifetime: these apply only to short-lived objects the
  caller drops within the loop or call (iterators, cursors, small result holders, lambdas).
  Sharing a long-lived object, or one that escapes anyway (stored, returned up the stack, kept), is
  still a win.
  - *Special-casing a path:* returning a shared sentinel (an empty iterator, a cached empty cursor
    or result) on some paths and a fresh object on others. To C2 the result is a merge of "new" and
    "existing", and it won't scalar-replace a merged allocation, so the fresh object becomes a real
    allocation on the common path too. Fix: return a new object of the same class on every path,
    the empty case included; keep any shared object out of the short-lived role.
  - *Inheriting shared bytecode for a hot traversal:* an immutable collection iterated on a hot
    path that inherits `AbstractList`'s or `AbstractCollection`'s iterator, `isEmpty()` or
    `Iterable.forEach`. That bytecode, and its profile, is shared by every subclass in the
    application, so its `size()`/`get()` calls go megamorphic, aren't inlined, and the iterator
    escapes. A quiet benchmark JVM hides it; measured with the profile polluted first, an inherited
    iterator cost 160 B and about 5× per walk, and `PrintInlining` showed the calls as virtual.
    Fix: give the class its own iterator, `forEach` and `isEmpty`, so their calls see one class.
    Mixing list classes at one call site (`emptyList`/`singletonList`/`ArrayList`) does the same
    to the caller's own sites, even unpolluted.
  - *Evidence to cite:* `EscapeShapeBenchmark` (internal-api JMH, `datadog.trace.util.escape`)
    measures these shapes across JDKs, in bytes per op on JDK 8 / 17 / 25: `singleSite` and
    `flagOnOneAllocation` 0 / 0 / 0, `mergeWithStatic` (the shared sentinel) 8 / 8 / 8,
    `mergeOfTwoAllocations` 16 / 16 / 16, `backingMegamorphic` (three implementations behind one
    site) 24, and `mergeWithNull` (`return ok ? new X() : null`) 8 / 8 / 0, which is still a trap
    on the Java 8 the tracer supports. `Maybe` is the worked example: one construction site, the
    outcome in a plain nullable field rather than a singleton, and `@NoEscape`;
    `MaybeUsagePatternsBenchmark#badMultiConstructionSite` shows two construction sites costing
    16 B/op.

  **flag-as-measure** ("may keep an object escape analysis would remove; verify with `-prof gc`,
  and with the shared profile polluted first by walking several other list classes before the trial,
  as `BenchmarkUtils.warmUpHashDispatch` does for hashing") — SEV-2/3.
- **J4 — GC pressure → *tail* latency** *(refines #1)*. Hot-path allocation → more GC → STW pauses
  → app **tail** latency, not just throughput (the tracer shares the app heap). ZGC has short
  pauses but isn't common — assume G1/Parallel. flag-as-measure ("may raise tail latency; verify
  under load at a realistic heap").

## Patterns

**Defensive copies at internal boundaries.** `array.clone()`, `new ArrayList<>(other)` — justified
at real trust boundaries (public API, genuinely mutable external input); wasteful
internal-to-internal where we control all callers. Fix: return a read-only view, or establish a
"don't mutate" contract.

**Capturing lambda on a hot path.** A non-capturing lambda is a cached singleton — zero alloc. A
capturing lambda (closes over a local or `this`) is a new instance per evaluation. Common trap:
`map.computeIfAbsent(k, k -> compute())` allocates the lambda on *every* call including cache hits
where it is never invoked. Fix: `get` first, `computeIfAbsent` only on miss.

**`Optional` and primitive boxing.** Any `Optional*` construction allocates per call and escapes.
Autoboxing outside the JVM cache range ([-128, 127] for `Integer`/`Long`) likewise. Fix: null
checks, primitive return values, or fixed-arity overloads.

*Examples (patterns from back-test calibration):*
- Capturing lambdas allocated per-span to register per-request callbacks. Allocation
  accepted: it buys correctness (fixes a span leak). Cost nominates; the do-no-harm hierarchy
  adjudicates.
- A per-instance `TagValue` on a per-trace path — the same shape as the regex and format-string
  cases in `repeat-work.md`.
