# Wrong collection type

Lighter wins: `LinkedHashMap` → `HashMap` → a plain record or value class.

## Heavier collection than needed

- **Flag:**
  - `LinkedHashMap` where nothing relies on its iteration order. It costs about 16 B more per
    entry and maintains a linked list on every put and remove;
  - `HashMap` for a small, fixed, known set of keys. It pays for hashing, boxing and an `Entry`
    object per mapping;
  - a collection that grows at runtime when its final size is known in advance (`ArrayList`
    grows by 1.5×, `HashMap` doubles and rehashes, and both copy on growth).
- **Not when:**
  - the order is needed (insertion order, or LRU with `accessOrder` and `removeEldestEntry`);
  - the keys are open-ended or come from outside;
  - the size isn't known when the collection is created.
- **Confidence / level:** flag-with-confidence. P3, raised for the per-span core.
- **Fix:** `HashMap` instead of `LinkedHashMap`; a record or value class instead of a small fixed
  map (denser, type-safe, and scalar-replaceable when it doesn't escape); pre-size accurately.
  `new HashMap<>(n)` still rehashes at 75% full, so on Java 8 use
  `new HashMap<>((int) (n / 0.75f) + 1)`. `HashMap.newHashMap(n)` is cleaner but needs JDK 19+.
- **Example:** a per-checkpoint `LinkedHashMap` replaced by a record-like value type removed the
  per-entry objects, the boxing and the linked list, for about 20% more throughput.

## Concurrent vs plain collections

- **Flag:** never as a directive. If a `ConcurrentHashMap` or other concurrent collection looks
  thread-confined, raise it as a question: "if this map is only used by one thread, a plain
  collection is cheaper; please check how it is accessed."
- **Why:** swapping a concurrent collection for a plain one on a wrong judgment adds a data race.
  That trades a performance cost for a correctness bug, which the do-no-harm order ranks worse.
