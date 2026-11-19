# Wrong collection type

SEV-2/3.

Three-step ladder: `LinkedHashMap → HashMap → POJO/record`. Lighter wins.

**`LinkedHashMap` when order isn't relied on.** ~16 B extra per entry + doubly-linked list
maintenance on every put/remove. Only justified when iteration order is required (insertion-order)
or for LRU (`accessOrder` + `removeEldestEntry`). Fix: `HashMap`.

**`HashMap` for a fixed, small, known key set.** Pays hashing, boxing, and `Entry` object overhead
per lookup. Fix: a plain record or value class — denser, EA-scalar-replaceable when non-escaping,
type-safe. A 5-line record is often *easier* to write than a map.

**Mis-sized collections.** `ArrayList` grows 1.5×; `HashMap` doubles and rehashes. Both pay
allocation + copy on growth. Fix: pre-size accurately at construction. Note: `new HashMap<>(n)`
still rehashes at 75% fill — pre-size with `new HashMap<>((int) (n / 0.75f) + 1)` (Java 8-safe);
`HashMap.newHashMap(n)` is cleaner but only where the source set is known JDK 19+.

**Concurrency choice — nominate, don't prescribe.** Replacing `ConcurrentHashMap` with `HashMap`
on a wrong concurrency judgment introduces a data race — trading a performance overhead for a
correctness bug, descending the do-no-harm hierarchy. Frame as a question ("if this map is
thread-confined, a plain collection is cheaper — verify the access pattern"), never a directive.

*Examples (patterns from back-test calibration):*
- A per-checkpoint `LinkedHashMap` collapsed to a record-like value type. The full
  three-step collapse: eliminated per-entry `Entry` overhead, boxing, and linked-list maintenance.
  ~20% throughput improvement.
- An oversized `StringBuilder(1024)` is the collection-sizing anti-pattern in a
  different form. Accurate sizing, not generous sizing, is the target.
