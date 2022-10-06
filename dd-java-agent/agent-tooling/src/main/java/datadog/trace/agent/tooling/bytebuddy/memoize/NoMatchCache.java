package datadog.trace.agent.tooling.bytebuddy.memoize;

import java.util.Arrays;

final class NoMatchCache {

  static final int MAX_CAPACITY = 1 << 16;
  static final int MIN_CAPACITY = 1 << 8;

  private final int[] elements;
  private final int keyMask;
  private final int valueMask;

  NoMatchCache(int capacity) {
    if (capacity < MIN_CAPACITY) {
      capacity = MIN_CAPACITY;
    } else if (capacity > MAX_CAPACITY) {
      capacity = MAX_CAPACITY;
    }
    // compute a power of two size for the given capacity
    int keySize = -1 >>> Integer.numberOfLeadingZeros(capacity - 1);
    this.elements = new int[keySize];
    this.keyMask = keySize - 1;
    this.valueMask = ~keyMask;
  }

  public void add(String name, ClassLoader loader) {
    int hash = name.hashCode();
    int slot = findSlot(hash, loader);
    if (slot < 0) {
      elements[~slot] = (hash & valueMask) | (System.identityHashCode(loader) & keyMask);
    }
  }

  public boolean contains(String name, ClassLoader loader) {
    return findSlot(name.hashCode(), loader) >= 0;
  }

  public void clear() {
    Arrays.fill(elements, 0);
  }

  private int findSlot(int hash, ClassLoader loader) {
    int firstSlot = hash & keyMask;
    int check = hash & valueMask;
    int slot = firstSlot;
    for (int i = 1; true; i++) {
      int current = elements[slot];
      if (0 == current) {
        return ~slot;
      } else if (check == current
          || (check | (System.identityHashCode(loader) & keyMask)) == current) {
        return slot;
      } else if (i == 3) {
        return ~firstSlot;
      }
      hash = rehash(hash);
      slot = hash & keyMask;
    }
  }

  private static int rehash(int oldHash) {
    return Integer.reverseBytes(oldHash * 0x9e3775cd) * 0x9e3775cd;
  }
}
