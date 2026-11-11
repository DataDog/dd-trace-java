# Performance-contract annotations

Checks #8, #9, #10. No lint enforces these yet; the AI reviewer is the only check.

These checks fire on code that never names the annotation: a field of type `SubSequence`, `Maybe`
or `ClassValue`, or a call into a method marked `@BackgroundOnly`. When a diff touches one of the
marked types or methods, read the annotation's own Javadoc. Each has a self-contained "Checker
contract" section with triggers, non-triggers and examples.

Every contract is "should", not "must": a trigger is a presumptive finding, not an automatic
failure. Any of them is satisfied by `@SuppressPerfContract(value = X.class, reason = "...")`
(`datadog.perfcontract.SuppressPerfContract`), or by a named annotation that is itself
meta-annotated `@SuppressPerfContract` with `X.class` in its `value`; that annotation's own
`reason` counts. Don't flag a suppression as non-compliant just because its reason is short.

## #8 `@NoEscape` field storage

- **Flag:** a field (instance or static, directly or as a generic type argument) whose declared
  type carries `@NoEscape` (`datadog.trace.api.function.NoEscape`), or that is initialized
  directly from a call to a method annotated `@NoEscape`, with no comment at the declaration
  explaining why the value is kept. Current types with the annotation: `SubSequence`, `Maybe`.
- **Not when:** the declaration has a `// Retained on purpose: <reason>`-style comment, or a
  suppression as described above.
- **Confidence / level:** flag-with-confidence. P3; P1 if the type or method shares backing storage
  with something large, as its Javadoc says.
- **Why:** the type form marks a concrete type such as `SubSequence`. The method form exists for
  return values whose concrete type can't carry the annotation (an anonymous class or lambda
  implementing a JDK interface). For `SubSequence`, see also J7 in `../remedies.md`, which tells
  transient views from retained ones.

## #9 `@StaticLifetime` field lifetime

- **Flag:** a field that is annotated `@StaticLifetime` (`datadog.perfcontract.StaticLifetime`),
  or whose declared type is listed in the `@ImpliedFor` on `StaticLifetime` (subtypes included),
  and that is neither `static final` nor a `final` instance field of a class annotated
  `@Singleton` (`datadog.perfcontract.Singleton`).
- **Not when:** the field has `@SuppressPerfContract(value = StaticLifetime.class, reason = ...)`,
  for a deliberate per-instance holder.
- **Confidence / level:** flag-with-confidence. P3.
- **Why:** the check only scans declarations. It does not try to prove a field is reused enough
  to be worth caching, and it does not catch an unannotated per-instance cache of a type that
  isn't listed. `@Singleton` is a trusted, unverified claim that the class has exactly one
  instance per process. The motivating defect was a cache constructed per request, so it never
  saved anything. See the annotation's Javadoc for the full table of triggers and accepted forms.

## #10 `@BackgroundOnly` reached from the foreground

- **Flag:** foreground code that directly calls code marked `@BackgroundOnly`
  (`datadog.trace.api.function.BackgroundOnly`); `@ForegroundSafe` marks where the foreground
  starts. Only direct calls count: a call that reaches `@BackgroundOnly` code through unmarked
  methods in between is out of scope.
- **Not when:**
  - the work is handed to a background thread (enqueued) instead of run;
  - background-only code calls `@ForegroundSafe` code;
  - the calling method has `@SuppressPerfContract(value = BackgroundOnly.class, reason = ...)`.
    This is also the form for the rare advice that runs on a thread the tracer owns.
- **Confidence / level:**
  - flag-with-confidence when the caller is marked `@ForegroundSafe` (or sits on a
    `@ForegroundSafe` type), or is an advice method (`@Advice.OnMethodEnter` /
    `@Advice.OnMethodExit`). Both ends are declared, and ByteBuddy inlines advice into the
    application's method, so advice is foreground by construction;
  - flag-as-measure when the caller is unmarked but sits on the hot-path map in `../hotness.md`
    (decorators, span create / setTag / finish, tag-map ops, propagation), because "foreground"
    is then inferred, not declared.
  - P2; P1 if the background-only work blocks (I/O, a contended lock, an unbounded loop).
- **Fix:** move the work to the background thread that owns it: enqueue it, or record the inputs
  and let the background drain compute.
- **Why:** background-only work (serialization, stats aggregation, eviction) is paced by a
  tracer-owned thread. On an application thread, the same cost becomes latency the customer sees.
