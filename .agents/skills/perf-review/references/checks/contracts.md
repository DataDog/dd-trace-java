# Performance-contract annotations

Checks #8, #9, #10. **No lint enforces these yet — the AI reviewer is the only check.**

These checks fire on code that never names the annotation: a field of type `SubSequence`, `Maybe`
or `ClassValue`, or a call into a method marked `@BackgroundOnly`. Read the annotation's own
Javadoc ("Checker contract") when a diff touches one of the marked types or methods.

## Checks

- **#8 — `@NoEscape` field-storage violation** — *a field (instance or static, directly or as a
  generic type argument) declared with an `@NoEscape`-annotated type
  (`datadog.trace.api.function.NoEscape`), **or** initialized directly from a call to an
  `@NoEscape`-annotated method — with no comment at the declaration justifying the retention*. The
  type form marks a concrete type (e.g. `SubSequence`); the method form exists for a return value
  whose concrete type can't itself carry the annotation (an anonymous class or lambda implementing
  a JDK interface). The annotation's own javadoc carries a self-contained "Checker contract"
  section (trigger / not-a-trigger / violation example / compliant example, both forms) written so
  this can be checked from the diff alone, with no other context needed. The underlying rule is
  "should", not "must" (RFC-2119 sense): a trigger is a presumptive finding, not an automatic
  failure — compliant if the declaration carries either a `// Retained on purpose: <reason>`-style
  comment, or `@SuppressPerfContract(value = NoEscape.class, reason = "...")`
  (`datadog.perfcontract.SuppressPerfContract` — the generic exemption mechanism shared by every
  perf-contract marker, not `@NoEscape`-specific), or a named canned-exception annotation itself
  meta-annotated `@SuppressPerfContract` with `NoEscape.class` among its `value` (e.g. a
  hypothetical `@Borrowed`) — treat the meta-annotation's own `reason` as satisfying the
  requirement. Don't flag a `SuppressPerfContract`/canned-exception use as non-compliant just for
  being terse; that mirrors the annotation's own Checker contract. **flag-with-confidence** —
  P3 (P1 if the annotated type/method shares backing storage with something large, per its
  own javadoc). Current wearers: `SubSequence`, `Maybe` (type form; see J7 in `../remedies.md` for
  `SubSequence`'s specific retention-vs-transient discriminator).
- **#9 — `@StaticLifetime` field-lifetime violation** — *a field annotated `@StaticLifetime`
  (`datadog.perfcontract.StaticLifetime`), **or** an unannotated field whose declared type is
  listed in the contract's `@ImpliedFor` (`datadog.perfcontract.ImpliedFor`; read the types from
  the `@ImpliedFor` on `StaticLifetime` itself, subtypes included), that is neither `static final`
  nor a `final` instance field declared on a class annotated `@Singleton`
  (`datadog.perfcontract.Singleton`)*. Declaration-scan only, same shape as the `@NoEscape`
  field-storage check above — it does not attempt to prove a field is actually reused enough to be
  worth caching, and it does not catch an unannotated per-instance cache of a non-implied type; it
  only guards fields that opt in or whose type implies the contract. A deliberate per-instance
  holder of an implied type is compliant with
  `@SuppressPerfContract(value = StaticLifetime.class, reason = "...")`. `@Singleton`, applied to a
  class, is a trusted, unverified declaration (v1 posture — the same stance the other
  perf-contract markers take toward `static final` itself) that the class has exactly one
  process-wide instance; declaring it satisfies `@StaticLifetime` for that class's `final` instance
  fields. **flag-with-confidence** — P3, motivated by a real production defect (a
  per-request-constructed cache that never actually amortized anything — see the annotation's own
  javadoc for the full trigger/accepted-form table).
- **#10 — `@BackgroundOnly` reached from the foreground** — *foreground code that directly calls
  background-only code, as defined by the checker contract in `@BackgroundOnly`'s Javadoc
  (`datadog.trace.api.function.BackgroundOnly`; `@ForegroundSafe` marks where the foreground
  starts)*. Background-only work (serialization, stats aggregation, eviction) is paced by a
  tracer-owned thread; run on an application thread, the same cost becomes customer-visible
  latency. Direct calls only: a call that reaches `@BackgroundOnly` code through unmarked
  intermediate methods is out of scope for the contract. Two levels of confidence:
  **flag-with-confidence** when the caller is marked `@ForegroundSafe` (or sits on a
  `@ForegroundSafe` type), or is an advice method (`@Advice.OnMethodEnter` /
  `@Advice.OnMethodExit`), because both ends are declared — ByteBuddy inlines advice into the
  instrumented application method, so it is foreground by construction; **flag-as-measure** when
  the caller is unmarked but sits on the hot-path map in `../hotness.md` (decorators, span
  create/setTag/finish, tag-map ops, propagation), because "foreground" is then inferred rather
  than declared. Advice on a thread the tracer owns is the rare exception; it takes
  `@SuppressPerfContract(value = BackgroundOnly.class, reason = "...")`. Not a trigger: handing the
  work to a background thread (enqueueing it) instead of running it; background-only code calling
  `@ForegroundSafe` code; a calling method with
  `@SuppressPerfContract(value = BackgroundOnly.class, reason = "...")`. P2 (P1 if the
  background-only work blocks — I/O, a contended lock, or an unbounded loop) — fix: defer the work
  to the background thread that owns it (enqueue, or record and let the background drain compute).
  A checker is tracked as APMLP-1645.
