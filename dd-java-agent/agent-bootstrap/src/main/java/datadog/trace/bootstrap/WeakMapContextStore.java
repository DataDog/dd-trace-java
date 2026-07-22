package datadog.trace.bootstrap;

import datadog.trace.api.Platform;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.util.AgentTaskScheduler;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Weak {@link ContextStore} that acts as a fall-back when field-injection isn't possible.
 *
 * <p>Entries are keyed weakly by identity and reclaimed once their carrier is collected: collected
 * keys are drained from a {@link ReferenceQueue} inline on every write — the only place the map can
 * grow — and by a periodic background task, so dead entries and their contexts don't accumulate on
 * stores that go idle or read-only. There is deliberately no size cap — growth is bounded by live
 * carriers, exactly like the injected-field path. A previous 50k cap silently dropped live trace
 * context under load when field injection was unavailable (issue #10479).
 *
 * <p>This class should be created lazily because it uses background cleanup.
 */
final class WeakMapContextStore<K, V> implements ContextStore<K, V> {
  private static final long CLEAN_FREQUENCY_SECONDS = 1;

  // Reused per thread to keep reads allocation-free; always cleared after use so it never
  // retains a carrier.
  private static final ThreadLocal<LookupKey> LOOKUP_KEY = ThreadLocal.withInitial(LookupKey::new);

  private final ConcurrentHashMap<Object, V> map = new ConcurrentHashMap<>();
  private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

  WeakMapContextStore() {
    if (!Platform.isNativeImageBuilder()) {
      AgentTaskScheduler.get()
          .weakScheduleAtFixedRate(
              ExpungeTask.INSTANCE,
              this,
              CLEAN_FREQUENCY_SECONDS,
              CLEAN_FREQUENCY_SECONDS,
              TimeUnit.SECONDS);
    }
  }

  @Override
  public V get(final K key) {
    // No expunge here: collected entries are unreachable via lookup anyway, and reads must stay
    // as cheap as possible. Dead entries are drained where the map grows — on writes.
    final LookupKey lookupKey = LOOKUP_KEY.get();
    try {
      return map.get(lookupKey.withReferent(key));
    } finally {
      lookupKey.clear();
    }
  }

  @Override
  public void put(final K key, final V context) {
    // Replace-in-place first: overwriting an existing carrier must not allocate and register
    // another WeakKey that would only become dead weight on the reference queue.
    final LookupKey lookupKey = LOOKUP_KEY.get();
    try {
      if (null != map.replace(lookupKey.withReferent(key), context)) {
        return;
      }
    } finally {
      lookupKey.clear();
    }
    expunge();
    map.put(new WeakKey(key, queue), context);
  }

  @Override
  public V putIfAbsent(final K key, final V context) {
    // Check with the reusable lookup key first: the hit path must not allocate.
    final V existingContext = get(key);
    if (null != existingContext) {
      return existingContext;
    }
    expunge();
    final V raceContext = map.putIfAbsent(new WeakKey(key, queue), context);
    return null != raceContext ? raceContext : context;
  }

  @Override
  public V putIfAbsent(final K key, final Factory<V> contextFactory) {
    // Hit path first so the adapting lambda is only allocated when the key is absent.
    final V existingContext = get(key);
    if (null != existingContext) {
      return existingContext;
    }
    return computeIfAbsent(key, ignored -> contextFactory.create());
  }

  @Override
  public V computeIfAbsent(final K key, final KeyAwareFactory<? super K, V> contextFactory) {
    V existingContext = get(key);
    if (null == existingContext) {
      // Serialized on this store (a reentrant monitor) rather than inside the map's own
      // computeIfAbsent: CHM forbids the mapping function from touching the map, and context
      // factories may re-enter this store. Same trade-off as before this store was rewritten:
      // the factory is only called once per key, except for a racing plain put which is
      // indistinguishable from a put happening right after.
      synchronized (this) {
        existingContext = get(key);
        if (null == existingContext) {
          existingContext = contextFactory.create(key);
          expunge();
          map.putIfAbsent(new WeakKey(key, queue), existingContext);
        }
      }
    }
    return existingContext;
  }

  @Override
  public V remove(final K key) {
    expunge();
    final LookupKey lookupKey = LOOKUP_KEY.get();
    try {
      return map.remove(lookupKey.withReferent(key));
    } finally {
      lookupKey.clear();
    }
  }

  @VisibleForTesting
  int size() {
    expunge();
    return map.size();
  }

  private void expunge() {
    Reference<?> ref;
    while ((ref = queue.poll()) != null) {
      map.remove(ref);
    }
  }

  // Explicit class to avoid an implicit hard reference to the store, which must stay collectible.
  private static final class ExpungeTask
      implements AgentTaskScheduler.Task<WeakMapContextStore<?, ?>> {
    static final ExpungeTask INSTANCE = new ExpungeTask();

    @Override
    public void run(final WeakMapContextStore<?, ?> target) {
      target.expunge();
    }
  }

  /** Weak identity key; equality with the stored referent or a {@link LookupKey} for it. */
  private static final class WeakKey extends WeakReference<Object> {
    private final int hash;

    WeakKey(final Object referent, final ReferenceQueue<Object> queue) {
      super(referent, queue);
      hash = System.identityHashCode(referent);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(final Object other) {
      if (other == this) {
        return true;
      }
      // A collected key can only equal itself; its map entry is removed via the reference queue.
      final Object referent = get();
      if (null == referent) {
        return false;
      }
      if (other instanceof WeakKey) {
        return referent == ((WeakKey) other).get();
      }
      return other instanceof LookupKey && referent == ((LookupKey) other).referent;
    }
  }

  /**
   * Strong query key, reused per thread; avoids allocating and registering a {@link WeakKey} for
   * read-only operations. Never stored in the map.
   */
  private static final class LookupKey {
    private Object referent;

    LookupKey withReferent(final Object referent) {
      this.referent = referent;
      return this;
    }

    void clear() {
      referent = null;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(referent);
    }

    @Override
    public boolean equals(final Object other) {
      if (other == this) {
        return true;
      }
      if (other instanceof WeakKey) {
        return referent == ((WeakKey) other).get();
      }
      return other instanceof LookupKey && referent == ((LookupKey) other).referent;
    }
  }
}
