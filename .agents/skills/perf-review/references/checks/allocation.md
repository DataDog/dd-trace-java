# Per-call and escaping allocation

Checks #1, #7, J1, J4.

The tracer shares the application's heap. Extra allocation means more GC, and GC pauses raise the
application's tail latency. The JVM's escape analysis removes short-lived allocations, but only
while the object stays local. Once it is stored in a map, returned, captured by a lambda, or
passed to a call that is not inlined, it escapes and the allocation is real.

## #1 Per-call allocation on a hot path

- **Flag:** an allocation that runs per span (or more often) and does not stay local: it is
  retained, returned, captured, or passed across a boundary. Common shapes:
  - a defensive copy (`array.clone()`, `new ArrayList<>(other)`) between internal classes where we
    control every caller;
  - a capturing lambda (one that uses a local or `this`), which is a new object each time. Watch
    `map.computeIfAbsent(k, k -> compute())`: the lambda is allocated even on a cache hit, when it
    is never called;
  - `Optional` construction, or autoboxing outside the JVM's cache range (−128 to 127 for
    `Integer` and `Long`).
- **Not when:**
  - the allocation is needed for correctness (for example, per-request callbacks that fix a span
    leak). Cost nominates; correctness wins;
  - the copy is at a real trust boundary (public API, or input that really is mutable from
    outside);
  - the lambda captures nothing; a non-capturing lambda is a cached singleton.
- **Confidence / level:** flag-with-confidence when it is clearly per span and retained;
  flag-as-measure when its lifetime or escape is unclear. P3, or P1 if it is unbounded.
- **Fix:** reuse, dense or positional storage, or move the work off the hot path. For the shapes
  above: return a read-only view or agree not to mutate; call `get` first and `computeIfAbsent`
  only on a miss; use null checks, primitive returns or fixed-arity overloads.

## #7 Escape analysis defeated by a refactor

- **Flag:** a change that makes a previously local object escape (stored, returned, captured by a
  closure, or passed to a virtual call that is not inlined), so a hot path now allocates.
- **Not when:** the object never leaves the method and every call it is passed to is inlined.
- **Confidence / level:** flag-as-measure ("may now escape and allocate; verify with an allocation
  profiler"). P3.
- **Fix:** keep the object local; remove the escaping store or capture.

## J1 Escaping allocation defeats escape analysis

- **Flag:** a per-span or per-tag object that is stored in the tag map, the span or a collection,
  iterated at serialization, or passed to a megamorphic call. It escapes, so the JIT cannot
  scalar-replace it.
- **Not when:** the object is transient: created, used and dropped inside code that inlines. Those
  objects are eligible for escape analysis and usually cost nothing.
- **Confidence / level:** flag-as-measure. P3, raised for the per-span core.
- **Fix:** store the data densely (positional fields or arrays) instead of in per-item wrapper
  objects.
- **Why:** "the JIT will scalar-replace it" is false for escaping objects. To check, use JFR: an
  object that escape analysis removed never shows up in an allocation profile, so if it appears,
  it escapes.
- **Example:** removing escaping per-tag wrapper objects from the tag store cut allocation by about
  half. An earlier change to similar entries that stayed transient measured about zero.

## J4 GC pressure raises tail latency

- **Flag:** new hot-path allocation, as a second consequence of #1: more allocation means more GC
  pauses, which raise the application's tail latency, not just its throughput.
- **Not when:** the allocation is rare or cold.
- **Confidence / level:** flag-as-measure ("may raise tail latency; verify under load with a
  realistic heap"). Same level as the #1 finding it adds to.
- **Why:** assume G1 or Parallel GC. ZGC has short pauses but is not common.
