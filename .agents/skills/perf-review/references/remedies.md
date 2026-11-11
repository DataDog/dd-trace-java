# Remedies

Where a finding from `checks/` should point for its fix. **These route an *existing* #1/#2/#3
finding to a reusable fix — they are not new flag-triggers. Don't raise a finding you wouldn't
have raised anyway; the posture (precision, silent-when-unsure, findings-cap-scales-with-diff —
see `SKILL.md`) is unchanged.**

## Toolkit availability — cite only what exists

Available today: `Strings`, `SubSequence`, `HashingUtils`, `Hashtable`, `ChunkedBuffer`
(+ `ChunkedStreamingBuffer` in `datadog.communication.serialization`),
`BenchmarkUtils.warmUpHashDispatch(Blackhole)` for JMH setup (all `datadog.trace.util` unless
noted), `RE2J` (`com.google.re2j`).

Coming (name as "coming", don't imply it's present): `ConcurrentHashtable`, `StringIndex`
(immutable string set/map), `UTF8BytesString.Cache` (recurring-string interner), wider
`IntegerCache` (http-status/port boxing), `DDCache` inlining.

## Routes

- **J7 — `substring`/slice → `SubSequence` zero-copy view** *(refines #1, #7)*.
  `String.substring`/`subSequence` allocates a fresh backing array per call. On a hot parse path
  (headers, tags, query strings, SQL/DBM, propagation) where the slice is **transient** — compared
  (`equals`/`startsWith`/`contains`/`indexOf`), parsed, or appended, then discarded — a
  `SubSequence` (offset+length view) is zero-copy and EA-elided **iff** the consumer takes a
  `CharSequence`/range (else the boundary `toString()` erases the win → add the overload or skip).
  **flag-as-measure** for the transient case (EA-dependent). The retention trap is
  **flag-with-confidence**: a `SubSequence` stored in a field/tag/collection/cache pins its
  *entire* backing String — a small window over a large string is a net memory loss — so a
  retained view must be materialized or `compact()`'d. Discriminator = transient (view, measure)
  vs retained (must detach). `SubSequence` carries `@NoEscape` — see #8 in `checks/contracts.md`
  for the general field-storage check this refines.
- **J9 — `Objects.hash(...)` varargs / boxing hash on a hot path → `HashingUtils`** *(refines
  #1)*. The allocation is specific to the **varargs/boxing forms**: `Objects.hash(a, b, …)`
  allocates an `Object[]` per call and **boxes every primitive** arg; same for boxing primitives
  into a `new Object[]{…}` (or `Arrays.hashCode` over such an array). Per-span tag/key building, or
  a hot value object's `hashCode()` built this way, → a guaranteed per-call allocation + boxing.
  Fix: `datadog.trace.util.HashingUtils` — primitive `hash(long/int/boolean/char/…)` overloads (no
  boxing), `hash(Object,Object)` and `hash(int,int)` combiners (no array); for >2 fields fold
  pairwise through `hash(int,int)` (there is no varargs form, by design). flag-with-confidence for
  the varargs/boxing form — SEV-2/3. **Do NOT flag allocation-free combines** — a hand-rolled
  `31*h + Long.hashCode(x)` / `31*h + intField`, or `Arrays.hashCode` over an *existing primitive
  array*, allocates nothing (`HashingUtils` is itself 31-based); flagging them would recommend
  replacing already-correct code.
- **J10 — hot-path `String.format` / string munging → `Strings` (+ `SubSequence`)** *(refines
  #2)*. `String.format` parses the format string, boxes its args, and allocates on every call —
  never on a hot path; hand-rolled case-conversion, class/resource-name munging, blank-checks, and
  truncation recomputed per call qualify too. Fix: `datadog.trace.util.Strings` —
  allocation-aware `replace`/`truncate(CharSequence)`/`isBlank`/`getResourceName`/`getClassName`/…;
  for **transient substring compares** prefer a `SubSequence` view (J7); for plain assembly,
  direct concatenation beats `format`. flag-with-confidence for `String.format` on a hot path;
  flag-as-measure for borderline munging — SEV-2/3.
- **J11 — composite / multi-dimensional key maps on a hot path → `Hashtable` /
  `ConcurrentHashtable`** *(refines #1, #3)*. `Map<A, Map<B,V>>` nesting, or a `HashMap` keyed by
  a composite key (client-side stats, per-`(service, operation, …)` aggregation), allocates nested
  maps + `Entry` objects + boxes keys on the hot aggregation path. Fix:
  `datadog.trace.util.Hashtable` (single-threaded, composite-key D1/D2 tables — landed) or
  `datadog.trace.util.ConcurrentHashtable` (lock-free concurrent, **coming**) — positional
  composite keys, fewer allocations. flag-as-measure — SEV-2/3.
