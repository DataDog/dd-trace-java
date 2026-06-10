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

## Storage

One global slot layout (today's model — all span types share the slot numbering; per-span-type
layouts are an open question, below). Sized to `KnownTags.slotCount()`.

```
byte[]   types   // per slot: UNSET=0 | OBJECT | CHARSEQUENCE | BOOLEAN | INT | LONG | FLOAT | DOUBLE
long[]   prims   // per slot: boolean(0/1) / int / long / float-bits / double-bits   (no boxing)
Object[] objs    // per slot: String/CharSequence/Object values; null for primitive slots
```

- **Presence** = `types[slot] != UNSET`. `size` maintained as a counter (+ bucket count).
- **Lazily allocated** on first known-tag write (like today's `knownEntries`).
- **Primitive arrays optional:** `prims` can be allocated lazily — most tags are strings (`objs`),
  so a primitive-free span never allocates `prims`.
- **Unknown tags** (no slot: `globalSerial == 0` or `fieldPos == NO_SLOT`) fall back to the
  existing hash buckets, which still use `Entry`. These are the minority (dynamic / rare tags);
  the common known tags are the ones we de-allocate.

Memory: trades *N* per-tag `Entry` objects (N = known tags on the span) for up to 3 fixed
per-span arrays. Net win when a span carries more than ~2–3 known tags (PetClinic spans carry
5–10) and especially on the serialize path (zero transient `Entry`).

## Write path

```
set(long id, value):
  pos = fieldPos(id)
  if pos < slotCount:  types[pos]=T; prims[pos]=packed  OR  objs[pos]=value   // no Entry
  else:                bucketSet(id, value)                                   // Entry (unknown)

set(String name, value):
  id = keyOf(name)                 // resolver
  if id != 0: set(id, value)       // known string set ALSO avoids Entry now
  else:       bucketSet(name,...)  // unknown
```

Interception (the 3-case routing in `DDSpanContext.setTag`) is unchanged and sits *above* this —
the table is just the storage the non-intercepted / post-interceptor write lands in.

## Read path

- **Typed getters** (`getString(id)`, `getInt(id)`, `getBoolean(id)`…) read the arrays directly —
  no allocation (boxing only if `getObject` is called on a primitive slot).
- **`getEntry(id)` / `getEntry(String)`** (API compat): materialize an `Entry` *on demand* from the
  slot — allocation happens only when a caller explicitly asks for an `Entry`, which is rare on the
  hot path now that typed getters and the serializer cursor exist.

## The payoff: no-`Entry` serialize

The real allocation win needs the **serializer to consume the table without materializing `Entry`**.
Add a no-alloc cursor:

```
forEachKnown(visitor):              // visitor.accept(name, type, primValue, objValue)
  for pos in 0..slotCount:
    if types[pos] != UNSET:
      visitor.accept(slotName(pos), types[pos], prims[pos], objs[pos])
```

`slotName(pos)` comes from the layout (generated `String[] slotNames`, or
`KnownTags.Resolver.nameOfSlot(int)`). The msgpack `TraceMapper` is adapted to write
`(name, typed value)` from this cursor instead of iterating `Entry` objects. Unknown/bucket tags
are iterated separately (they still have `Entry`s). Result: a span's known tags serialize with
**zero `Entry` allocation**.

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
(or bucket). **Recommendation: model #1 for the experiment** (products don't perturb the AVT layout),
promote a hot `applies: all` product via #2 if measurement warrants, treat #3 as the long-term tight
design alongside span-type-at-creation.

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
