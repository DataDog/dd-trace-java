package com.datadog.iast.util;

import java.lang.reflect.Array;
import javax.annotation.Nullable;

/**
 * Naive implementation of an HTTP header in a case-insensitive way.
 *
 * <p><b>Warning</b> case insensitive only works for characters in the range [A-Za-z]
 */
public class HttpHeaderMap<T> {

  private static final int DEFAULT_BUCKETS = 1 << 4;

  private static final class Entry<T> {

    private final String key;

    @Nullable private T value;

    @Nullable private Entry<T> next;

    private Entry(final String key, @Nullable final T value) {
      this.key = key;
      this.value = value;
    }
  }

  private final Entry<T>[] entries;
  private final int bucketCount;
  private final boolean powerOfTwo;
  private final int mask;

  @SuppressWarnings("unchecked")
  public HttpHeaderMap(int bucketCount) {
    this.bucketCount = bucketCount;
    this.powerOfTwo = (bucketCount & (bucketCount - 1)) == 0;
    this.mask = bucketCount - 1;
    this.entries = (Entry<T>[]) Array.newInstance(Entry.class, bucketCount);
  }

  public HttpHeaderMap() {
    this(DEFAULT_BUCKETS);
  }

  @Nullable
  public T put(final String header, @Nullable final T value) {
    if (header == null) {
      return null;
    }
    final int index = index(hash(header));
    Entry<T> cur = entries[index];
    if (cur == null) {
      entries[index] = new Entry<>(header, value);
    } else {
      while (true) {
        if (equals(cur.key, header)) {
          final T currentValue = cur.value;
          cur.value = value;
          return currentValue;
        }
        if (cur.next == null) {
          cur.next = new Entry<>(header, value);
          return null;
        }
        cur = cur.next;
      }
    }
    return null;
  }

  @Nullable
  public T get(final String name) {
    if (name == null) {
      return null;
    }
    final int index = index(hash(name));
    Entry<T> entry = entries[index];
    while (entry != null) {
      if (equals(entry.key, name)) {
        return entry.value;
      }
      entry = entry.next;
    }
    return null;
  }

  @Nullable
  public T remove(final String name) {
    if (name == null) {
      return null;
    }
    final int index = index(hash(name));
    Entry<T> cur = entries[index], prev = null;
    while (cur != null) {
      if (equals(cur.key, name)) {
        if (prev == null) {
          entries[index] = cur.next;
        } else {
          prev.next = cur.next;
        }
        return cur.value;
      }
      prev = cur;
      cur = cur.next;
    }
    return null;
  }

  public int size() {
    int result = 0;
    for (Entry<T> cur : entries) {
      while (cur != null) {
        result++;
        cur = cur.next;
      }
    }
    return result;
  }

  private int index(final int hash) {
    return powerOfTwo ? (hash & mask) : (hash % bucketCount);
  }

  /** Case insensitive hash */
  private static int hash(final String name) {
    int h = 0;
    for (int i = 0; i < name.length(); i++) {
      char c = lowerCase(name, i);
      h = 31 * h + c;
    }
    if (h > 0) {
      return h;
    } else if (h == Integer.MIN_VALUE) {
      return Integer.MAX_VALUE;
    } else {
      return -h;
    }
  }

  /** Case insensitive equals * */
  private static boolean equals(final String name1, final String name2) {
    if (name1.length() != name2.length()) {
      return false;
    }
    for (int i = 0; i < name1.length(); i++) {
      char c1 = lowerCase(name1, i);
      char c2 = lowerCase(name2, i);
      if (c1 != c2) {
        return false;
      }
    }
    return true;
  }

  /** It works for our subset of headers */
  private static char lowerCase(final String string, final int index) {
    final char c = string.charAt(index);
    if (c >= 'A' && c <= 'Z') {
      return (char) (c + 32); // 'a' - 'A'
    }
    return c;
  }
}
