# Repeated work on hot paths

Checks #2, J8. SEV-2/3 (J8 up to SEV-1).

Tracing is repetitive. Work repeated per-span or per-trace compounds quickly. The focus is on
*allocating* repeat work — patterns that produce garbage the GC must collect — not pure CPU-micro
work like an `.equals()` call.

## Checks

- **#2 — Repeat work across calls/traces** — *string concat / case-conversion / regex *compile* /
  format / parse recomputed each hot-path call, on a recurring (low-cardinality) input or
  allocating each time* — flag-with-confidence — SEV-2/3 — fix: memoize (bounded — see #3) or
  compile-once (hoist regex to static).
- **J8 — Backtracking regex on external input → RE2J / bounded input** *(distinct from #2
  compile-per-call)*. `java.util.regex` backtracks → exponential worst-case (ReDoS) on adversarial
  input — a CPU / tail-latency / DoS hazard, **not** an allocation one. Flag the **conjunction**:
  (a) input is user/external-controllable AND (b) the pattern is backtracking-prone
  (nested/overlapping quantifiers, `(a+)+`, unanchored `.*` around a quantified group).
  **flag-with-confidence** when both hold — SEV-2 (tail latency), **SEV-1** on a per-request
  AppSec/security-scan path (IAST Reporter / WAF run regex on untrusted input every request). Fix:
  RE2J (`com.google.re2j`, guaranteed linear; no backrefs/lookaround), anchor/de-nest, or hard-cap
  input length.

## Patterns

**Regex compile per call.** `Pattern.compile(...)` at a non-static site allocates and compiles on
every call. Fix: `static final Pattern`.

**`String.format` / format-string parsing.** Re-parses and allocates per call. Fix: direct
concatenation, or pre-compute the result. Also watch for locale-dependent formatting crossing the
wire — a correctness issue on top of the perf one.

**`Config.get()` per call.** Walks a config-resolution chain; not a free read. Fix: hoist to a
`static final` field at class initialization. This is the single most recurring DBM finding —
surfaced independently in five separate PRs.

**Java Streams on hot paths.** `stream()` and `parallelStream()` always allocate `Spliterator`
and pipeline objects. JIT elision is fragile — small changes to the pipeline or surrounding code
break it silently. Fix: plain `for` loop. Any of `cstyleFor`, `enhancedFor`, `forEach`, or
`iterator` are equivalent and zero-allocation. Benchmark evidence (Java 17, M1, 8 threads,
@Fork(2)): plain loops allocate ≈ 10⁻⁷ B/op; `stream()` always allocates 56–88 B/op;
`parallelStream()` scales from 128 B/op (empty list) to 5 200 B/op (100-element list).

*Examples (patterns from back-test calibration):*
- A per-trace path with regex compile per call + `String.format` + a locale-dependent formatting
  bug — the clearest recall case for mechanism-certain patterns.
- A `StringBuilder(1024)` per query for a ~200-character result. Two-sided error:
  under-size causes realloc, over-size wastes memory. Target accurate, not generous.
