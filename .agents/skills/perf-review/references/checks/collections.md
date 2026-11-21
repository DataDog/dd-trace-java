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

**J17 — Lock guarding publishable state → compare-and-set on an immutable snapshot** *(policy from
2026-10-22, APMLP-2100)*. **Prefer a CAS over `synchronized` / `Lock` whenever possible.** Flag a
**new or added** `synchronized` block or `ReentrantLock` whose critical section only reads the
current state, computes the next state and writes it back (one field, or several that could form one
immutable snapshot), and any **per-instance lock object** (`private final Object lock = new
Object()`) on a per-span or per-trace type. Mechanism: from JDK 15 (biased locking off by default,
removed in 18) a monitor enter is itself a CAS on the object header, so the lock costs the atomic
**plus** monitor bookkeeping, often plus a lock object allocated per instance; `synchronized` also
pins virtual-thread carriers on JDK 21–23, and under contention a lock parks threads where a CAS
retries. Measured on `PTags`' per-trace sampling lock (2026-10-22): −24 B per `PTags` on every JDK
and +7–28% throughput in span-level benchmarks on JDK 17/21. The only loss was JDK 8/11 with default
flags, where biased locking makes an uncontended `synchronized` nearly free; that cost is accepted,
as those JVMs are a shrinking minority (about 30% of orgs). Fix: an immutable snapshot behind one
volatile field, updated by a CAS loop through an `AtomicReferenceFieldUpdater` — compute the next
state from the one read, return it unchanged for a no-op (and skip the CAS), retry on failure.
**Correctness is the review, not a measure-nudge.** Check: (a) any state the lock also covered
outside the snapshot must move into it, or be ordered so that a racing writer's CAS fails and it
retries (e.g. clear a flag *before* the competing CAS) — stress tests rarely hit a wrong order, so
reason about it; (b) the compute step runs again on every retry, so it must not have non-idempotent
side effects (clearing identity-keyed caches is fine); (c) ABA is safe when every successful CAS
installs a freshly allocated snapshot; (d) a concurrency test should race two operations whose
outcome is the same in either order, so a lost update fails it. **Don't flag** where a lock is still
right: an invariant that can't reasonably be one immutable snapshot, state whose per-update copy
costs more than the lock (large or mutable collections), or a critical section with blocking or
non-repeatable work. For code that must stay optimal on JDK 8/11, the adaptive immutable-state
updater (APMLP-2099, **coming**) will choose `synchronized(owner)` there. flag-with-confidence on a
per-span or per-trace type, flag-as-consider elsewhere — SEV-2/3.

*Examples (patterns from back-test calibration):*
- A per-checkpoint `LinkedHashMap` collapsed to a record-like value type. The full
  three-step collapse: eliminated per-entry `Entry` overhead, boxing, and linked-list maintenance.
  ~20% throughput improvement.
- An oversized `StringBuilder(1024)` is the collection-sizing anti-pattern in a
  different form. Accurate sizing, not generous sizing, is the target.
