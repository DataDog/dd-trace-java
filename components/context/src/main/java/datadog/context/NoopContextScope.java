package datadog.context;

import java.lang.ref.WeakReference;

/** {@link ContextScope} that has no effect on execution units. */
final class NoopContextScope extends WeakReference<Context> implements ContextScope {
  private static final ContextScope ROOT_SCOPE = new NoopContextScope(Context.root());

  private static final int CACHE_SIZE = 32; // must be power of 2
  private static final int SLOT_MASK = CACHE_SIZE - 1;
  private static final int MAX_HASH_ATTEMPTS = 3;

  /** Bounded cache of no-op scopes to reduce (re)allocations. */
  private static final NoopContextScope[] cache = new NoopContextScope[CACHE_SIZE];

  @SuppressWarnings({"resource", "StatementWithEmptyBody"})
  static ContextScope create(Context context) {
    if (context == Context.root()) {
      return ROOT_SCOPE;
    }
    int hash = System.identityHashCode(context);
    int evictedSlot = -1;
    // search by repeated hashing; stop when we find an empty slot,
    // a matching slot, or we exhaust all attempts and re-use a slot
    for (int i = 1, h = hash; true; i++, h = rehash(h)) {
      int slot = SLOT_MASK & h;
      NoopContextScope existing = cache[slot];
      if (existing != null) {
        // slot already used
        Context existingContext = existing.get();
        if (context == existingContext) {
          return existing; // match found
        }
        if (i < MAX_HASH_ATTEMPTS) {
          // still more slots to search
          if (existingContext == null && evictedSlot < 0) {
            // record first evicted slot for re-use later
            evictedSlot = slot;
          }
          continue; // rehash and try again
        }
        // exhausted attempts, pick best slot to re-use
        if (evictedSlot >= 0) {
          slot = evictedSlot; // re-use first evicted slot
        } else if (existingContext == null) {
          // last hashed slot is itself evicted, re-use it
        } else {
          slot = SLOT_MASK & hash; // re-use first hashed slot
        }
      }
      return (cache[slot] = new NoopContextScope(context));
    }
  }

  private NoopContextScope(Context context) {
    super(context);
  }

  @Override
  public Context context() {
    Context context = get();
    // no-op scopes are used when the context is already attached so the reference
    // value should still be there; if not then we fall back to empty (root) context
    return context != null ? context : Context.root();
  }

  @Override
  public void close() {}

  private static int rehash(int oldHash) {
    return Integer.reverseBytes(oldHash * 0x9e3775cd) * 0x9e3775cd;
  }
}
