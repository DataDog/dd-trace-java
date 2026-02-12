package datadog.trace.api.cache;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntFunction;

/** Sparse cache of values associated with a small integer */
public final class RadixTreeCache<T> {
  private static final IntFunction<UTF8BytesString> TO_STRING =
      value -> UTF8BytesString.create(Integer.toString(value));

  public static final int UNSET_STATUS = 0;
  // should cover range [0, 512) to cover all standard HTTP statuses
  // 16 pages of 32 should keep the tree sparse with typical pages
  // covering ranges [192, 224), [288, 320), [384, 416), [480, 512)
  public static final RadixTreeCache<UTF8BytesString> HTTP_STATUSES =
      new RadixTreeCache<>(
          16, 32, TO_STRING, 200, 201, 301, 307, 400, 401, 403, 404, 500, 502, 503);

  public static final int UNSET_PORT = 0;

  private final int level1;
  private final int level2;
  private final int shift;
  private final int mask;

  private final AtomicReferenceArray<Object[]> tree;
  private final IntFunction<T> mapper;

  public RadixTreeCache(int level1, int level2, IntFunction<T> mapper, int... commonValues) {
    this.tree = new AtomicReferenceArray<>(level1);
    this.mapper = mapper;
    this.level1 = level1;
    this.level2 = level2;
    this.mask = level2 - 1;
    this.shift = Integer.bitCount(mask);
    for (int commonValue : commonValues) {
      get(commonValue);
    }
  }

  public T get(int primitive) {
    int prefix = primitive >>> shift;
    // bounds check both ends with one check
    if (prefix >= level1) {
      return mapper.apply(primitive);
    }
    return computeIfAbsent(prefix, primitive);
  }

  @SuppressWarnings("unchecked")
  @SuppressFBWarnings("DCN") // only interested in catching NullPointerException (see note below)
  private T computeIfAbsent(int prefix, int primitive) {
    Object[] page = tree.get(prefix);
    if (null == page) {
      try {
        page = new Object[level2];
        if (!tree.compareAndSet(prefix, null, page)) {
          page = tree.get(prefix);
        }
      } catch (NullPointerException e) {
        // Intermittent NPE observed in JDK code on Semeru 11.0.29: java.lang.NullPointerException:
        // at j.l.i.ArrayVarHandle$...Operations$OpObject.computeOffset(ArrayVarHandle.java:142)
        // at j.l.i.ArrayVarHandle$...Operations$OpObject.compareAndSet(ArrayVarHandle.java:201)
        // at j.u.c.atomic.AtomicReferenceArray.compareAndSet(AtomicReferenceArray.java:152)
        // at datadog.trace.api.cache.RadixTreeCache.computeIfAbsent(RadixTreeCache.java:59)
        // Location indicates JDK's VarHandle used to access the backing array has returned null
        // To mitigate this rare JDK bug we still map the primitive but skip caching the result
        return mapper.apply(primitive);
      }
    }
    // it's safe to race here
    int suffix = primitive & mask;
    Object cached = page[suffix];
    if (cached == null) {
      cached = page[suffix] = mapper.apply(primitive);
    }
    return (T) cached;
  }
}
