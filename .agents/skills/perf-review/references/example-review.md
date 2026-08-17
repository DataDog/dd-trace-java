# Perf Review — PR #11903 (Bucket4j instrumentation, demo)

**PR:** https://github.com/DataDog/dd-trace-java/pull/11903
**Rubric:** `checks.md` + `guide.md` (this skill's references)
**Scope reviewed:** `Bucket4jDecorator.onConsume` — runs on every `Bucket#tryConsume` call (tracing hot path; multiplier = per-call × calls/sec).
**Method:** Diff reviewed independently of the PR description (description ignored per request).

## Confirmed findings

### 1. Per-call config lookup + Set allocation
```java
InstrumenterConfig.get().isIntegrationEnabled(singleton("bucket4j-tier"), true)
```
`Collections.singleton(...)` allocates a new `SingletonSet` every call, plus a config lookup, for a value that doesn't change per-call.
- **Confidence:** flag-with-confidence
- **Rubric check:** #2 (repeat work on invariant input)
- **Severity:** SEV-2/3
- **Fix:** hoist to a `static final boolean` (or cache in a field) computed once; eliminate the per-call allocation.

### 2. `Arrays.stream(...).filter(...).findFirst()` in the hot path
Builds a Stream pipeline (Stream + Spliterator + pipeline stages + captured lambda) every call just to find the first threshold ≥ tokens.
- **Confidence:** flag-with-confidence
- **Rubric check:** #1 / #5 (per-call allocation + unnecessary indirection)
- **Severity:** SEV-3
- **Fix:** plain `for` loop over `TIER_THRESHOLDS`, no Stream.

### 3. `Objects.hash(bucket, tokens, consumed)` in `onConsume`
Varargs `Object[]` allocation + boxing of `tokens` (long) and `consumed` (boolean) on every call. Runs unconditionally, not gated behind the tier flag.
- **Confidence:** flag-with-confidence
- **Rubric check:** J9
- **Severity:** SEV-2/3
- **Fix:** `datadog.trace.util.HashingUtils` (no boxing, no array).

### 4. Eager string concatenation in `LOGGER.debug(...)`
```java
LOGGER.debug("bucket4j tryConsume tokens=" + tokens + " consumed=" + consumed + " bucket=" + bucket);
```
Builds the string (StringBuilder + `bucket.toString()`) unconditionally, even when debug logging is disabled.
- **Confidence:** flag-with-confidence
- **Rubric check:** #2 / J10
- **Severity:** SEV-2/3
- **Fix:** SLF4J parameterized form `LOGGER.debug("bucket4j tryConsume tokens={} consumed={} bucket={}", tokens, consumed, bucket)`, or guard with `isDebugEnabled()`.

## Correctly suppressed (not flagged)

`private static final int DEFAULT_LIMIT_KEY = Objects.hash("default", 100L);`

Textually the same `Objects.hash` pattern as finding #3, but this one runs once at class-init (cold path), not per-call. Per the rubric's precision-over-recall posture (silent when unsure / don't erode trust with lookalike false positives), this is correctly **not** flagged.

## Checked, no issue

- No unbounded memory / cardinality-sensitive aggregator (check #3, J5) — nothing cached.
- No FFI/native-boundary crossing (check #6, J3).
- No megamorphic-dispatch finding raised — J2 is explicitly parked in the rubric, not an active review idiom.
- String-literal tag keys (`"bucket4j.tier"`, etc.) — JVM interns literals automatically, no per-call allocation cost.

## Summary

4 confirmed hot-path findings, all SEV-2/3 (allocation/CPU — none unbounded or OOM-adjacent). 1 lookalike correctly suppressed as cold-path.
