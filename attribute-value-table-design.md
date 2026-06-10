# Dense known-tag storage (a.k.a. AttributeValueTable) — design

Branch: `dougqh/attribute-value-table` (off `dougqh/tagmap-tagid-experiment`)

## Goal

Eliminate the **per-tag `TagMap$Entry` allocation** — the #1 remaining tracer allocator
(~1.1% of process allocation in the PetClinic JFR, even after the tag-id work). The tag-id
fast-path made tag *placement* fast (positional slot vs hash bucket) but still allocates one
`Entry` wrapper per tag set, and keeps it alive until serialize.

**Idea:** for known tags, store the *values* in a dense `(id, value)` pair array — no
`Entry` object per tag. A span's known tags never materialize an `Entry`; the serializer reads
`(name, type, value)` straight from the arrays.

## Phasing

- **Phase 1 (this design): replace `OptimizedTagMap`'s `Entry[] knownEntries` in place** with dense
  `long[] ids` + `Object[] values`. No new type, no interface, no codegen. This is purely an internal
  storage change to one class, and it *removes* machinery as much as it adds (see below). It's the
  measurable step that kills the per-tag `Entry` for known tags.
- **Phase 2 (later, if warranted): extract an `AttributeValueTable` interface + a codegen POJO** per
  hot span type (real typed fields, no bounds checks, type-reject for free). Extracting the interface
  from a *working* dense impl is an easy refactor — and we'll know its true shape from having built
  it, rather than guessing now. The `set(long)→boolean` / `get(long)→EntryReader` contract below is
  where that interface is headed; in phase 1 it's just how `OptimizedTagMap` works internally.

Everything below describes **phase 1** unless marked otherwise.

## What phase 1 removes

Replacing the positional `Entry[] knownEntries` with a dense scan-by-id store deletes the collision
machinery the positional slot model needed: first-writer-wins occupancy, the `collidedSlots` bitmask,
and bucket-eviction-on-reclaim. Dense `(id, value)` pairs have no positional collisions — you match by
id. `fieldPos`/`slotCount` stop mattering for storage (identity, name, and hash all come from the id);
they stay in the tagId for the eventual POJO but the dense store ignores them.

## Storage — dense parallel arrays

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
strings (no box), and a boxed `Integer` is smaller than the `Entry` it replaces, so still a net win.

A parallel `long[] prims` to avoid that boxing was **considered and rejected**: it adds a whole extra
per-span array *and* per-entry type tracking (which array holds the value), which costs more than the
handful of small boxes it would save. Single `Object[] values`, box the few fresh primitives.

Prebuilt/shared `Entry`s holding a primitive are **not** a loss either: `Entry` caches its boxed value,
so a write sourced from a prebuilt `EntryReader` stores that *shared* box (`entry.objectValue()`) —
zero per-span allocation, same as today. So the only boxing is a fresh, per-span-varying primitive set
via the typed `set(long, int/...)` overloads — negligible. No real regression.

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
   (replace the `Entry[] knownEntries` with the dense `ids`/`values` arrays), rather than a new
   top-level type — keeps the whole interface working.
2. Known-tag get/set/remove/iterate operate on the dense arrays; bucket paths unchanged.
3. `Entry`-returning methods materialize lazily via `EntryReader.entry()`.
4. Reuse the existing `Iterable<EntryReader>` serialize path (flyweight per entry) — no new cursor.

## Open questions

1. **Initial array capacity / growth.** Starting size for `ids`/`values` and growth policy (spans
   carry ~5–15 tags; pick a sensible default to avoid resizes without over-allocating tiny spans).
2. **`Ledger` / builder path** — how accumulated changes apply to the dense arrays.
3. **Scan vs index at larger N.** If some span types carry many tags, confirm the linear scan still
   wins; otherwise a small index is an option (but adds cost the dense form is trying to avoid).

Resolved during design: dense parallel arrays over positional-by-`fieldPos` (mixins become plain
pairs); single `Object[] values` over a parallel `long[] prims` (the extra array + type tracking
cost more than the few boxes); reads/serialize via the existing `EntryReader` rather than a bespoke
visitor; no separate `Layout` (consult the resolver, + `typeOf`).

## Performance: the trade, eyes open

- **Write path (frequent): better** — scan + append into `ids`/`values`, no per-tag `Entry`.
- **Allocation / GC: better** — removes the 1.1% `Entry` lever; less GC (CPU the profile attributes
  elsewhere). A typical (string-heavy) span allocates two arrays instead of N `Entry`s.
- **Read / serialize: some extra CPU per tag** — flyweight reposition + array read + `nameOf` +
  coercion dispatch, vs today's `Entry` that caches name and typed value. **This is intrinsic to a
  generic store** — you cannot match direct-field access without generating the fields (the POJO
  endgame). Mitigations (lean flyweight, near-no-op coercion when the stored type matches) narrow it
  but do not erase it.

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
