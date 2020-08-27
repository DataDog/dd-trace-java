package datadog.trace.bootstrap.instrumentation.cache;

import java.util.concurrent.atomic.AtomicReferenceArray;

/** Sparse cache of commonly boxed values */
public final class RadixTreeBoxCache {

  // should cover range [0, 512) to cover all standard HTTP statuses
  // 16 pages of 32 should keep the tree sparse with typical pages
  // covering ranges [192, 224), [288, 320), [384, 416), [480, 512)
  public static final RadixTreeBoxCache HTTP_STATUSES =
      new RadixTreeBoxCache(16, 32, 200, 201, 301, 307, 400, 401, 403, 404, 500, 502, 503);

  // should cover range [0, 2^16)
  public static final RadixTreeBoxCache PORTS = new RadixTreeBoxCache(256, 256, 80, 443, 8080);

  private final int level1;
  private final int level2;
  private final int shift;
  private final int mask;

  private final AtomicReferenceArray<Integer[]> tree;

  RadixTreeBoxCache(int level1, int level2, int... commonValues) {
    assert Integer.bitCount(level1) == 1
        && Integer.bitCount(level2) == 1
        && level1 > 0
        && level2 > 0;
    this.tree = new AtomicReferenceArray<>(level1);
    this.level1 = level1;
    this.level2 = level2;
    this.mask = level2 - 1;
    this.shift = Integer.bitCount(mask);
    for (int commonValue : commonValues) {
      box(commonValue);
    }
  }

  public Integer box(int primitive) {
    int prefix = primitive >>> shift;
    // bounds check both ends with one check
    if (prefix >= level1) {
      return primitive;
    }
    return boxIfNecessary(prefix, primitive);
  }

  private Integer boxIfNecessary(int prefix, int primitive) {
    Integer[] page = tree.get(prefix);
    if (null == page) {
      page = new Integer[level2];
      if (!tree.compareAndSet(prefix, null, page)) {
        page = tree.get(prefix);
      }
    }
    // it's safe to race here
    int suffix = primitive & mask;
    Integer boxed = page[suffix];
    if (boxed == null) {
      boxed = page[suffix] = primitive;
    }
    return boxed;
  }
}
