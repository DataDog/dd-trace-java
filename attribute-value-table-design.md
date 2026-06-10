# AttributeValueTable — design

Branch: `dougqh/attribute-value-table` (off `dougqh/tagmap-tagid-experiment`)

## Goal

Eliminate the **per-tag `TagMap$Entry` allocation** — the #1 remaining tracer allocator
(~1.1% of process allocation in the PetClinic JFR, even after the tag-id work). The tag-id
fast-path made tag *placement* fast (positional slot vs hash bucket) but still allocates one
`Entry` wrapper per tag set, and keeps it alive until serialize.

**Idea:** for known (slotted) tags, store the *values* positionally in typed arrays — no
`Entry` object per tag. A span's known tags never materialize an `Entry`; the serializer reads
`(name, type, value)` straight from the arrays.

This is the runtime counterpart to the [`tag-conventions.yaml`](tag-conventions.yaml) spec: the
generator assigns each known tag a `fieldPos`, and the `AttributeValueTable` is indexed by it.

## An interface, not one storage scheme

`AttributeValueTable` is an **interface**. The opaque `set(long)→boolean` / `get(long)→EntryReader`
contract leaks nothing about storage, so the same interface can be satisfied by either backing:

- **Array/segment-backed** (generic, resolver-driven) — the measurable first impl; no codegen.
- **POJO-backed** (codegen, per span type) — a generated class with real typed fields + generated
  `set`/`get` switches. Densest and most JIT-friendly (fields inline, no bounds checks); type-reject
  falls out for free (a wrong-type `set` finds no matching field → returns `false`). Lazily-created
  mixin sub-POJOs for products.

Callers (`OptimizedTagMap`) are impl-agnostic — the array impl ships first, the POJO impl can replace
it per span type later with no caller change.

## Storage (array-backed impl) — dense parallel arrays

A **dense association list of only the tags actually present** — not arrays sized to the slot count:

```
long[]   ids     // the tag id of each present known tag, in insertion order
Object[] values  // its value (boxed if primitive)
int      size    // number of used entries (arrays grow as needed)
```

- **`set(id, v)`**: scan `ids[0..size)` for a match (overwrite) else append. No `Entry`. Returns
  `true` (stored) — unknown ids / type mismatches return `false` and the caller buckets them.
- **`get(id)`**: scan `ids` for the match → flyweight `EntryReader` over `(nameOf(id), values[i])`.
- **iterate/serialize**: dense walk of `ids[0..size)`; name = `nameOf(ids[i])`, value = `values[i]`.
- **Unknown tags** (`globalSerial == 0`) and **type mismatches** fall back to the hash buckets (still
  `Entry`) — the minority.

Why dense rather than positional-by-`fieldPos`:
- **Mixins need no special machinery** — a product tag is just another `(id, value)` pair; the list
  holds only what's set, so disabled/unused products cost nothing. No segments, presence bitmask, or
  `fieldPos` partition.
- **The id is stored**, so iteration names a tag directly (`nameOf`) — no `fieldPos → id` reverse map.
- **Maps onto `EntryReadingHelper`** (already in `LegacyTagMap`): a reusable `EntryReader` holding
  `(tag, Object value)` with coercion via `TagValueConversions` and `EntryReader.entry()` to
  materialize. The flyweight per index is `(nameOf(ids[i]), values[i])`. Almost nothing new.
- **`fieldPos` stops mattering** for the generic store — identity + name come from the id; positional
  field layout is the POJO specialization's concern only.

Trade-offs (accepted): **O(n) scan** instead of O(1)-by-position — fine in the small-map regime
(spans carry ~5–15 tags; a packed `long[]` scan is cache-friendly, and the common path is set-once +
one dense serialize pass). **Boxing** of the few primitive tags (status_code, port) — most tags are
strings (no box), and a boxed `Integer` is smaller than the `Entry` it replaces, so still a net win;
if a primitive-heavy span type shows it in the JMH, add a parallel `long[] prims` aligned with `ids`
(value in `prims` when primitive, `values[i] = null`).

Prebuilt/shared `Entry`s holding a primitive are **not** a loss: `Entry` caches its boxed value, so a
write sourced from a prebuilt `EntryReader` stores that *shared* box (`entry.objectValue()`) — zero
per-span allocation, same as today. The only residual boxing is a **fresh, per-span-varying primitive**
set via the typed `set(long, int/...)` overloads (status_code, port) — a tiny small-box cost,
removable later via the parallel `long[] prims` if it ever shows. So no real regression.

### Type discipline

The resolver declares each tag's type (`typeOf`). `set` accepts a value only if it matches; otherwise
it returns `false` and the caller buckets it as a normal `Entry`. Off-type writes degrade gracefully
instead of corrupting the slot. Type *coercion on read* (e.g. int → string for serialization) is
`EntryReader`'s job (via `TagValueConversions`), not a widening of the stored value.

Memory: trades *N* per-tag `Entry` objects for two arrays (`ids` + `values`) sized to the tags
present, plus a box per primitive tag. Net win when a span carries more than ~2–3 known tags
(PetClinic spans carry 5–10), and especially on the serialize path (zero transient `Entry`).

## Write path

```
table.set(long id, value):                   // returns true iff stored
  if globalSerial(id) == 0 || !typeMatches(id, value): return false   // unknown / wrong type
  for i in 0..size:                           // small-n linear scan
      if ids[i] == id: values[i] = value; return true                 // overwrite
  ids[size] = id; values[size] = value; size++; return true           // append, no Entry

// caller (OptimizedTagMap):
if (!table.set(id, value)) setInBuckets(id, value)                    // Entry (unknown / off-type)
// string set: id = keyOf(name); known -> table.set; else -> buckets
```

Interception (the 3-case routing in `DDSpanContext.setTag`) is unchanged and sits *above* this —
the table is just the storage the non-intercepted / post-interceptor write lands in.

## Read path

All reads go through **`get(long) → EntryReader`** (a repositioned flyweight, the `EntryReadingHelper`
pattern). `EntryReader`'s own accessors + `TagValueConversions` provide value reads and type coercion
(e.g. int → string for serialization) in one place — so there are no separate typed getters on the
table, and slot-stored vs bucket-stored values coerce identically. Materialize a retainable `Entry`
only when a caller needs to hold one, via the existing `EntryReader.entry()`.

## The payoff: no-`Entry` serialize

`TagMap` is already `Iterable<EntryReader>` and the msgpack `TraceMapper` already consumes
`EntryReader` — so the table reuses that contract with **no serializer change and no bespoke visitor**.
`iterator()` does a dense walk of `ids[0..size)` and yields the repositioned flyweight `EntryReader`
(name = `nameOf(ids[i])`, value = `values[i]`). `OptimizedTagMap` chains the table's readers then its
bucket `Entry`s (also `EntryReader`s). Result: a span's known tags serialize with **zero `Entry`
allocation**; only unknown/bucket tags retain `Entry`s.

## How product mixins interact

The dense representation makes this nearly a non-question: **a product tag is just another `(id, value)`
pair**. The list holds only the tags actually set, so a span that doesn't trigger profiling/dsm/appsec
simply has none of their pairs — zero cost, decided per span, with no segments, no layout composition,
and no need for the span type at creation. `applies` stays a *codegen* concern (which span types may
emit which product tags / whether a product tag earns a stable id at all); it no longer shapes the
runtime storage. (The earlier positional-segment scheme — `fieldPos = [segment][offset]`, lazily
allocated per mixin — is moot under dense arrays and was dropped.)

## API

`AttributeValueTable` is the **slotted-only** store; `OptimizedTagMap` owns the hash buckets and
the composition. The key shape: **`set` returns whether it stored the value** — a `false` tells the
caller to place it in the buckets. The table knows nothing about buckets; routing is explicit and
the "did it slot?" check happens once, inside `set`.

The table consults the registered `KnownTags.Resolver` directly (like `OptimizedTagMap` already uses
`KnownTags.slotCount()`) — no separate `Layout` object. The dense store needs only **one** addition
the codegen already knows: `typeOf(long)` (for type-reject + the reader's `type()`). No reverse
`fieldPos → id` lookup is needed — the id is stored, so iteration names a tag via `nameOf(ids[i])`.

```java
public final class AttributeValueTable {        // backed by KnownTags.Resolver (global layout)

  // write: @return true if stored in a slot; false => caller must bucket it
  public boolean set(long tagId, CharSequence value);
  public boolean set(long tagId, Object value);
  public boolean set(long tagId, boolean value);
  public boolean set(long tagId, int value);
  public boolean set(long tagId, long value);
  public boolean set(long tagId, float value);
  public boolean set(long tagId, double value);

  public boolean remove(long tagId);   // @return true if a slot was cleared
  public void clear();

  public boolean remove(long tagId);
  public boolean contains(long tagId);
  public int size();

  // read: returns a FLYWEIGHT EntryReader positioned at the matching entry (or null if absent).
  // EntryReader's own type()/objectValue()/<typed> accessors cover value reads, so no
  // separate getString/getInt/... and no separate Visitor are needed.
  // NOTE: transient view — valid until the next table op; not retainable.
  public TagMap.EntryReader get(long tagId);

  // iteration yields the repositioned flyweight EntryReader -> plugs into the existing
  // Iterable<EntryReader> serialize path with ZERO per-tag allocation.
  public Iterator<TagMap.EntryReader> iterator();
}
```

Read model: `TagMap` is already `Iterable<EntryReader>` and the msgpack writer already consumes
`EntryReader`, so the table reuses that contract — no bespoke visitor and no separate typed getters
(`EntryReader`'s own coercion covers reads, shared via `TagValueConversions`). `get`/`iterator`
return a **flyweight** `EntryReader` (the `EntryReadingHelper` pattern — one reusable cursor
repositioned per entry), so no `Entry` per tag. `OptimizedTagMap`'s iterator chains the table's
readers then its bucket `Entry`s (also `EntryReader`s) — uniform. Materialize a retainable `Entry`
via the existing `EntryReader.entry()` when a caller needs to hold it (the flyweight is otherwise a
transient view, valid until the next table op).

Composition + the three tiers:

```java
// OptimizedTagMap.set(long id, <type> value)
if (!table.set(id, value)) setInBuckets(id, value);
//   slotted known    -> table stores, returns true
//   unslotted known  -> table returns false -> bucket (id-bearing Entry)
//   unknown (keyOf==0)-> caller buckets directly
```

(Open: add a `getAndSet`-style variant only if a caller needs the prior value; `set->boolean`
covers the common write path.)

## API-compat strategy

`TagMap` is a large `Entry`-centric interface. Plan:
1. Implement `AttributeValueTable` as an alternative storage *inside* `OptimizedTagMap`
   (replace the `Entry[] knownEntries` with the three arrays), rather than a new top-level type —
   keeps the whole interface working.
2. Slot get/set/remove/iterate operate on the arrays; bucket paths unchanged.
3. `Entry`-returning methods materialize lazily.
4. Add the `forEachKnown` cursor and wire the serializer.

## Open questions

1. **Global vs per-span-type layout.** Global (today) is simplest and needs no span-type at
   creation, but sizes every span's arrays to the union of all known tags (~40+ with full
   conventions). Per-span-type layout (from the YAML) is tighter but requires knowing the span type
   when the span is created — a bigger change. *Recommend: start global, measure, then evaluate
   per-type.*
2. **Serializer integration depth.** The `forEachKnown` cursor is the crux of the allocation win;
   without it we only save on the write path. Worth doing for the real number.
3. **Primitive packing layout.** Single `long[] prims` + `byte[] types`, vs a tagged `Object[]`
   with boxing — measure whether the extra arrays pay off vs just `Object[]` + box.
4. **`Ledger` / builder path** — how accumulated changes apply to arrays.
5. **Memory floor for tiny spans** — spans with 1–2 known tags: do the 3 arrays cost more than they
   save? (lazy `prims`, and a small-size threshold, mitigate this.)

## Performance: the trade, eyes open

- **Write path (frequent): better** — set a bit + write one array slot, no per-tag `Entry`.
- **Allocation / GC: better** — removes the 1.1% `Entry` lever; less GC (CPU the profile attributes
  elsewhere). With lazy `prims`, a typical (string-heavy) span allocates fewer objects than today.
- **Read / serialize: some extra CPU per tag** — flyweight reposition + array read + name resolve +
  coercion dispatch, vs today's `Entry` that caches name and typed value. **This is intrinsic to a
  generic, layout-driven store** — you cannot match direct-field access without generating the fields.
  Mitigations (static `slotNames` index, lean flyweight, near-no-op coercion when the stored type
  matches) narrow it but do not erase it.

Why it's acceptable: the array-backed impl accepts that small read cost as the **price of generality**
(any tag, no codegen, no span-type-at-creation); **POJOs recover it for hot span types** on the same
interface. You pay the indirection only where you haven't specialized — i.e. where you don't care.
The net is likely neutral-to-positive even pre-POJO (cheaper frequent writes + lower GC; serialize is
a single pass per span); POJOs make it clearly positive where it counts.

## How we'll measure

**Standalone JMH first, three-way**, on a realistic PetClinic-like tag set (component, span.kind,
db.*, http.*), measuring throughput and **allocation (`-prof gc`)**:
1. today's `OptimizedTagMap` (`Entry[]`) — the baseline,
2. array-backed `AttributeValueTable` — does it regress read CPU? how much alloc does it save?
3. a **hand-written POJO** for one span type (e.g. `db.client`) — confirms the codegen endgame wins
   enough to justify building the generator.

If array-backed is promising (or break-even on CPU with the alloc win), integrate it (incl. the
`EntryReader` serialize path) and re-run the PetClinic CPU/alloc A/B with the existing harness; build
codegen POJOs for the hot span types once the hand-POJO confirms the payoff.
