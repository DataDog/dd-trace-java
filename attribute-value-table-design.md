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

## Storage (array-backed impl)

Slots are organized into lazily-allocated **segments** (segment 0 = structural, 1+ = product mixins;
see "How product mixins interact"). Each slot's **type is declared by the resolver** (`typeOf`, from the YAML `type:`) and is therefore static — the table stores no per-span type array. Per segment:

```
long     present  // presence bitmask (long[] if the segment has > 64 slots) — 0 is a valid primitive
Object[] objs     // object/CharSequence-typed slots
long[]   prims    // primitive-typed slots: boolean(0/1)/int/long/float-bits/double-bits  (no boxing)
```

- **Type is static per slot** (`KnownTags` `typeOf`); the flyweight reader derives `type()` from it.
- **Lazily allocated** per segment on first write; `objs`/`prims` each lazy (a primitive-free segment
  never allocates `prims`). `size` = popcount(present) + bucket count.
- **Unknown tags** (`globalSerial == 0`, `fieldPos == NO_SLOT`) and **type mismatches** fall back to
  the hash buckets (still `Entry`) — the minority; correctly-typed known tags are what we de-allocate.

### Type discipline

The resolver declares each slot's type (`typeOf`). `set` accepts a value only if it matches; otherwise it returns
`false` and the caller buckets it as a normal `Entry`. Slots stay mono-typed (so type need not be
stored per span) and off-type writes degrade gracefully instead of corrupting a slot. Type *coercion
on read* (e.g. int → string for serialization) is `EntryReader`'s job (via `TagValueConversions`),
not a widening of the stored value.

### Reuse (read side already exists)

The flyweight reader is the `EntryReadingHelper` pattern already used by `LegacyTagMap`: a reusable
`EntryReader` repositioned per slot, coercion delegated to `TagValueConversions`, and
`EntryReader.entry()` for materialize-on-demand. No new reader, visitor, or coercion code.

Memory: trades *N* per-tag `Entry` objects (N = known tags on the span) for a small presence bitmask
plus up to two lazily-allocated arrays per occupied segment. Net win when a span carries more than
~2–3 known tags (PetClinic spans carry 5–10) and especially on the serialize path (zero transient
`Entry`).

## Write path

```
table.set(long id, value):                   // returns true iff stored in a slot
  pos = fieldPos(id)
  if pos < slotCount && typeMatches(id, value):
      present |= bit(pos);  prims[pos]=packed  OR  objs[pos]=value    // no Entry
      return true
  return false                                // no slot or wrong type

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
`iterator()` walks occupied slots and yields the repositioned flyweight `EntryReader` (name via
`resolver.tagIdAt(pos)` → `nameOf`, value from the arrays). `OptimizedTagMap` chains the table's slot
readers then its bucket `Entry`s (also `EntryReader`s). Result: a span's known tags serialize with
**zero `Entry` allocation**; only unknown/bucket tags retain `Entry`s.

## How product mixins interact with the layout

The structural inheritance (`base → http → http.server`) is the **stable, build-time** part of
the layout — those tags map to fixed slots. Product mixins (profiling, dsm, appsec, ci) are the
**dynamic** part: present only when enabled, attached by `applies`. So the question is whether/how
they consume slots. Three models:

1. **Unslotted product tags (recommended first).** Mixin tags are `slot: false` → keep an id but
   live in the buckets. AVT slot layout = structural tags only. Products add few tags, so the bucket
   cost is negligible, and **disabled products cost zero** per-span array space. Clean split:
   *structure → slots, enrichment → buckets*; preserves the fixed-layout property.

2. **Layout composed at registration.** `slotCount`/layout is assembled at init from
   `structural + enabled products`. An `applies: all` product (profiling/dsm) extends the universal
   slot region — its tag gets a slot on every span *only when enabled*; disabled products contribute
   nothing. Fits the existing model (`slotCount` is a dynamic constant captured at resolver
   registration; codegen emits each mixin's slot contribution, runtime concatenates the enabled
   ones). `applies: [types]` products don't fit a single global layout (they'd waste global slots on
   other types).

3. **Per-span-type layouts.** Each type's AVT = resolved structural tags + product tags whose
   `applies` matches. Tightest; `applies: [http.server]` appends appsec's slots to exactly that type.
   Requires the span type at creation — the bigger change.

`applies` is exactly the composer's signal: `all` → universal-region candidate; `[types]` → per-type
(or bucket).

### Recommended: lazy per-mixin segments

Because `set(tagId)→bool` / `get(tagId)→EntryReader` hide the storage strategy from callers, the table
can organize itself as **segments**, each lazily allocated:

```
segment 0  = structural tags (base + the span type's inherited/own tags)   — the common case
segment 1+ = one per product mixin (profiling, dsm, appsec, ci, …)         — allocated on first touch
```

- The `fieldPos` field partitions into `[segment : 4][offset : 12]`, so a `tagId` names its segment
  and intra-segment offset directly — no extra lookup.
- `set` routes to `Segment[segOf(fieldPos)]`, allocating a mixin segment **on its first touch on this
  span**; a span that never sets a product's tag never allocates that segment.
- `get`/iteration walk segment 0 + whatever mixin segments exist.

This beats the three models above: product tags get positional, no-`Entry` storage *when present*
(unlike "always bucket"), with zero per-span cost *when absent* — decided **per span**, with no
registration-time composition and no need for the span type at creation. Each mixin = a segment in
codegen; `applies` tells codegen which span types can light up which segments; structural inheritance
is segment 0. Cost: one extra indirection (segment index + null-check) on `set`/`get`; the common
path (segment 0 only) is a single array deref either way.

## API

`AttributeValueTable` is the **slotted-only** store; `OptimizedTagMap` owns the hash buckets and
the composition. The key shape: **`set` returns whether it stored the value** — a `false` tells the
caller to place it in the buckets. The table knows nothing about buckets; routing is explicit and
the "did it slot?" check happens once, inside `set`.

The table consults the registered `KnownTags.Resolver` directly (like `OptimizedTagMap` already uses
`KnownTags.slotCount()`) — no separate `Layout` object. The resolver gains two additions the codegen
already knows: `typeOf(long)` (for type-reject + the reader's `type()`) and `tagIdAt(int fieldPos)`
(only for iteration, to name a slot walked by index).

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

  // read: returns a FLYWEIGHT EntryReader positioned at the slot (or null if absent).
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
repositioned per slot), so no `Entry` per tag. `OptimizedTagMap`'s iterator chains the table's slot
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

## How we'll measure

Per the agreed plan: **standalone JMH first** — `AttributeValueTable` vs `OptimizedTagMap` on a
realistic PetClinic-like tag set (component, span.kind, db.*, http.*), measuring throughput and
**allocation (`-prof gc`)**. Expect ~zero `Entry` allocs for known tags vs N today. If promising,
integrate (incl. the serializer cursor) and re-run the PetClinic CPU/alloc A/B with the existing
harness.
