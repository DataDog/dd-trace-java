package com.datadog.iast.util;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@Warmup(iterations = 2, time = 1000, timeUnit = MILLISECONDS)
@Measurement(iterations = 3, time = 1000, timeUnit = MILLISECONDS)
@Fork(3)
@OutputTimeUnit(NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Thread)
public class HttpHeaderMapBenchmark {

  private static final String HEADER = "Content-Type";

  private final HttpHeaderMap<Integer> optimized = new HttpHeaderMap<>(64);
  private final BaselineMap<Integer> baseline = new BaselineMap<>(64);

  public HttpHeaderMapBenchmark() {
    optimized.put(HEADER, 1);
    baseline.put(HEADER, 1);
  }

  @Benchmark
  public Integer optimized() {
    return optimized.get(HEADER);
  }

  @Benchmark
  public Integer baseline() {
    return baseline.get(HEADER);
  }

  /**
   * Copy of {@link HttpHeaderMap} that always uses modulo for bucket index calculation even when
   * the bucket count is a power of two.
   */
  private static final class BaselineMap<T> {

    private static final class Entry<T> {
      private final String key;
      private T value;
      private Entry<T> next;

      private Entry(final String key, final T value) {
        this.key = key;
        this.value = value;
      }
    }

    private final Entry<T>[] entries;
    private final int bucketCount;

    @SuppressWarnings("unchecked")
    private BaselineMap(int bucketCount) {
      this.bucketCount = bucketCount;
      this.entries = (Entry<T>[]) java.lang.reflect.Array.newInstance(Entry.class, bucketCount);
    }

    public T put(final String header, final T value) {
      int index = index(hash(header));
      Entry<T> cur = entries[index];
      if (cur == null) {
        entries[index] = new Entry<>(header, value);
        return null;
      }
      while (true) {
        if (equals(cur.key, header)) {
          T old = cur.value;
          cur.value = value;
          return old;
        }
        if (cur.next == null) {
          cur.next = new Entry<>(header, value);
          return null;
        }
        cur = cur.next;
      }
    }

    public T get(final String name) {
      int index = index(hash(name));
      Entry<T> entry = entries[index];
      while (entry != null) {
        if (equals(entry.key, name)) {
          return entry.value;
        }
        entry = entry.next;
      }
      return null;
    }

    private int index(final int hash) {
      return hash % bucketCount;
    }

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

    private static char lowerCase(final String string, final int index) {
      final char c = string.charAt(index);
      if (c >= 'A' && c <= 'Z') {
        return (char) (c + 32);
      }
      return c;
    }
  }
}
