# Repeated work on hot paths

Checks #2, J8.

Tracing is repetitive, so work repeated per span or per trace adds up quickly. The focus is on
repeated work that allocates, not on pure CPU work like an extra `.equals()`.

## #2 Repeated work across calls

- **Flag:** work recomputed on every hot-path call for input that recurs (low cardinality), or
  that allocates each time:
  - string concatenation, case conversion, or formatting of values that don't change;
  - `Pattern.compile(...)` at a non-static site;
  - `String.format`, which parses the format string and allocates on every call;
  - `Config.get()` / `InstrumenterConfig.get()`, which walks the config-resolution chain and is
    not a free read;
  - Java Streams (`stream()`, `parallelStream()`), which always allocate a `Spliterator` and
    pipeline objects;
  - a buffer sized much larger than its typical result (for example `new StringBuilder(1024)` per
    query for a ~200-character result).
- **Not when:**
  - the input has open cardinality, so a cache would rarely hit (see "Not when" on #3 in
    `memory.md`);
  - the code is cold: startup, a one-time init, or a rare error path;
  - for Streams: the stream is inside a cache-miss body or a length-guarded error path.
- **Confidence / level:** flag-with-confidence. P3, raised for the per-span core.
- **Fix:** memoize (with a bound; see #3 in `memory.md`) or compute once: `static final Pattern`,
  hoist `Config.get()` to a `static final` field or the constructor, use direct concatenation
  instead of `String.format`, a plain `for` loop instead of a stream, and size buffers for the
  typical result. Under-sizing reallocates and over-sizing wastes memory: aim for accurate, not
  generous.
- **Why:** on JDK 17 (M1, 8 threads), plain loops allocate about 0 B/op, `stream()` allocates
  56–88 B/op, and `parallelStream()` 128–5,200 B/op depending on list size.
- **Example:** a per-trace path with a regex compiled on every call and a `String.format`. The
  format was also locale-dependent and put a comma decimal on the wire, which is a correctness bug
  for the correctness lens.

## J8 Backtracking regex on external input

- **Flag:** a `java.util.regex` pattern that is prone to backtracking (nested or overlapping
  quantifiers such as `(a+)+`, or an unanchored `.*` around a quantified group) applied to input a
  user or another external party controls. Both conditions must hold.
- **Not when:** the input is trusted (our own formatter's output, config written by the operator),
  or the pattern has no nested or overlapping quantifiers.
- **Confidence / level:** flag-with-confidence. P2; P1 on a per-request AppSec or security-scan
  path (IAST Reporter, WAF), which runs regex on untrusted input on every request.
- **Fix:** RE2J (`com.google.re2j`, guaranteed linear time, no backreferences or lookaround),
  anchor or de-nest the pattern, or cap the input length.
- **Why:** this is a CPU, tail-latency and denial-of-service risk (ReDoS), not an allocation
  problem. It is separate from #2's compile-per-call.
