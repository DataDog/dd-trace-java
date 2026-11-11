# Remedies

Where a finding should point for its fix. J7, J9, J10 and J11 route an existing #1, #2 or #3
finding to a reusable fix; they are not new triggers. J7's retention trap and J9's varargs form
are the exceptions: they decide on their own, so they are written as checks.

## Toolkit availability: cite only what exists

Available today (in `datadog.trace.util` unless noted): `Strings`, `SubSequence`, `HashingUtils`,
`Hashtable`, `ConcurrentHashtable`, `StringIndex`, `ChunkedBuffer`, `ChunkedStreamingBuffer` (in
`datadog.communication.serialization`), `BenchmarkUtils.warmUpHashDispatch(Blackhole)` for JMH
setup, and RE2J (`com.google.re2j`).

Not built yet; if you mention one, say it is coming: `UTF8BytesString.Cache` (an interner for
recurring strings), a wider `IntegerCache` (boxing of HTTP status codes and ports), `DDCache`
inlining.

## J7 `substring` → `SubSequence` view

- **Flag:** `String.substring` / `subSequence` on a hot parse path (headers, tags, query strings,
  SQL, propagation) where the slice is transient: compared (`equals`, `startsWith`, `contains`,
  `indexOf`), parsed or appended, then dropped. Separately, flag a `SubSequence` stored in a
  field, tag, collection or cache: it keeps its whole backing `String` alive, so a small window
  over a large string loses memory.
- **Not when:** the consumer only takes a `String`. The `toString()` at the boundary undoes the
  gain, so either add a `CharSequence` or range overload, or skip it.
- **Confidence / level:** transient slice: flag-as-measure (it depends on escape analysis), P3.
  Retained view: flag-with-confidence, P3.
- **Fix:** a `SubSequence` (offset and length) for the transient case. For a retained view, make
  a `String` from it or call `compact()`. `SubSequence` carries `@NoEscape`; see #8 in
  `checks/contracts.md`.

## J9 `Objects.hash(...)` → `HashingUtils`

- **Flag:** `Objects.hash(a, b, …)` on a hot path, which allocates an `Object[]` on every call and
  boxes every primitive argument. The same goes for boxing primitives into `new Object[]{…}`, or
  `Arrays.hashCode` over such an array, for example in per-span key building or a hot value
  object's `hashCode()`.
- **Not when:** the combine allocates nothing: a hand-written `31 * h + Long.hashCode(x)` or
  `31 * h + intField`, or `Arrays.hashCode` over an existing primitive array. `HashingUtils` is
  also 31-based, so flagging these would replace correct code.
- **Confidence / level:** flag-with-confidence. P3, raised for the per-span core.
- **Fix:** `datadog.trace.util.HashingUtils`: primitive `hash(long / int / boolean / char …)`
  overloads (no boxing), and `hash(Object, Object)` and `hash(int, int)` combiners (no array). For
  more than two fields, fold pairwise through `hash(int, int)`; there is no varargs form, by
  design.

## J10 `String.format` and string munging → `Strings`

- **Route from:** a #2 finding on `String.format` or on hand-written case conversion, class or
  resource name munging, blank checks or truncation recomputed per call.
- **Fix:** `datadog.trace.util.Strings` (`replace`, `truncate(CharSequence)`, `isBlank`,
  `getResourceName`, `getClassName` and others, all allocation-aware). For transient substring
  comparisons, prefer a `SubSequence` view (J7). For plain assembly, direct concatenation beats
  `format`.
- **Confidence / level:** flag-with-confidence for `String.format` on a hot path; flag-as-measure
  for borderline munging. P3.

## J11 Composite-key maps → `Hashtable` / `ConcurrentHashtable`

- **Route from:** a #1 or #3 finding on `Map<A, Map<B, V>>` nesting, or on a `HashMap` keyed by a
  composite key (client-side stats, per-`(service, operation, …)` aggregation), which allocates
  nested maps and `Entry` objects and boxes keys on the hot aggregation path.
- **Fix:** `datadog.trace.util.Hashtable` (single-threaded, composite-key D1 / D2 tables) or
  `datadog.trace.util.ConcurrentHashtable` (concurrent): positional composite keys and fewer
  allocations.
- **Confidence / level:** flag-as-measure. P3.
