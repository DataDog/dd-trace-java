package com.datadog.profiling.otel;

import java.util.Arrays;

/**
 * Open-addressing hash map from a fixed number of long key components to an int value, avoiding
 * boxing in the profile conversion hot path. Any long value is a legal key component (occupancy is
 * tracked in a separate array, so there are no sentinel keys).
 */
public final class LongKeyIntMap {
  private final int arity;
  // arity longs per slot, laid out consecutively: slot i uses keys[i * arity .. i * arity + arity)
  private long[] keys;
  private int[] values;
  private byte[] used;
  private int mask;
  private int size;

  public LongKeyIntMap(int arity, int initialCapacity) {
    if (arity < 1) {
      throw new IllegalArgumentException("arity must be positive: " + arity);
    }
    int cap = Integer.highestOneBit(Math.max(initialCapacity * 2, 16) - 1) << 1;
    this.arity = arity;
    keys = new long[cap * arity];
    values = new int[cap];
    used = new byte[cap];
    mask = cap - 1;
  }

  /** Lookup with a single key component. Returns -1 when absent. */
  public int get1(long k1) {
    int slot = (int) (mix(k1) & mask);
    while (used[slot] != 0) {
      if (keys[slot] == k1) {
        return values[slot];
      }
      slot = (slot + 1) & mask;
    }
    return -1;
  }

  /** Insert or replace with a single key component. */
  public void put1(long k1, int value) {
    if (size * 2 > mask) {
      resize();
    }
    int slot = (int) (mix(k1) & mask);
    while (used[slot] != 0) {
      if (keys[slot] == k1) {
        values[slot] = value;
        return;
      }
      slot = (slot + 1) & mask;
    }
    used[slot] = 1;
    keys[slot] = k1;
    values[slot] = value;
    size++;
  }

  /** Lookup with three key components. Returns -1 when absent. */
  public int get3(long k1, long k2, long k3) {
    int slot = (int) (mix3(k1, k2, k3) & mask);
    int base = slot * arity;
    while (used[slot] != 0) {
      if (keys[base] == k1 && keys[base + 1] == k2 && keys[base + 2] == k3) {
        return values[slot];
      }
      slot = (slot + 1) & mask;
      base = slot * arity;
    }
    return -1;
  }

  /** Insert or replace with three key components. */
  public void put3(long k1, long k2, long k3, int value) {
    if (size * 2 > mask) {
      resize();
    }
    int slot = (int) (mix3(k1, k2, k3) & mask);
    int base = slot * arity;
    while (used[slot] != 0) {
      if (keys[base] == k1 && keys[base + 1] == k2 && keys[base + 2] == k3) {
        values[slot] = value;
        return;
      }
      slot = (slot + 1) & mask;
      base = slot * arity;
    }
    used[slot] = 1;
    keys[base] = k1;
    keys[base + 1] = k2;
    keys[base + 2] = k3;
    values[slot] = value;
    size++;
  }

  public void clear() {
    Arrays.fill(used, (byte) 0);
    size = 0;
  }

  public int size() {
    return size;
  }

  private void resize() {
    long[] oldKeys = keys;
    int[] oldValues = values;
    byte[] oldUsed = used;
    int oldCap = mask + 1;
    int newCap = oldCap * 2;
    keys = new long[newCap * arity];
    values = new int[newCap];
    used = new byte[newCap];
    mask = newCap - 1;
    size = 0;
    for (int i = 0; i < oldCap; i++) {
      if (oldUsed[i] != 0) {
        int base = i * arity;
        if (arity == 1) {
          put1(oldKeys[base], oldValues[i]);
        } else {
          put3(oldKeys[base], oldKeys[base + 1], oldKeys[base + 2], oldValues[i]);
        }
      }
    }
  }

  private static long mix(long key) {
    key ^= key >>> 33;
    key *= 0xff51afd7ed558ccdL;
    key ^= key >>> 33;
    return key;
  }

  private static long mix3(long k1, long k2, long k3) {
    return mix(mix(k1) ^ k2) ^ mix(k3);
  }
}
