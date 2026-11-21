package com.datadog.openfeature.internal.evaluation;

import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Hands the flag evaluation hook each split's prepared evaluation features, so the hook doesn't
 * rebuild them from the evaluation metadata on every evaluation.
 *
 * <p>OpenFeature metadata only carries primitives, so a prepared split puts a {@code long} handle
 * in the metadata instead: the epoch of the configuration it was registered under, and its index in
 * that epoch's table. A new configuration starts a new epoch, which drops the old table, so the
 * table only ever holds the features of the configuration being served. A handle from an older
 * epoch resolves to {@code null}, and the hook falls back to reading the metadata entries, which
 * are still written.
 *
 * <p>Registration happens once per split, when it is first served, so it takes a lock; lookups
 * don't.
 */
public final class EvaluationFeatureHandles {
  /** Bounds the table if a configuration's splits are somehow prepared over and over. */
  static final int MAX_HANDLES = 1 << 16;

  private static final Object LOCK = new Object();
  private static volatile Epoch current = new Epoch(1, null);

  private EvaluationFeatureHandles() {}

  /** Starts a new epoch when the configuration being served changes. */
  public static void configurationChanged(final ServerConfiguration configuration) {
    synchronized (LOCK) {
      final Epoch epoch = current;
      if (epoch.configuration != configuration) {
        current = new Epoch(epoch.id + 1, configuration);
      }
    }
  }

  /**
   * Registers prepared evaluation features in the current epoch.
   *
   * @return the handle, or {@code 0} if the table is full.
   */
  static long register(final Map<String, Object> features) {
    synchronized (LOCK) {
      final Epoch epoch = current;
      final int index = epoch.size;
      if (index >= MAX_HANDLES) {
        return 0L;
      }
      Map<String, Object>[] table = epoch.table;
      if (index == table.length) {
        table = Arrays.copyOf(table, table.length * 2);
      }
      table[index] = features;
      epoch.size = index + 1;
      // Written last, so a reader that sees the handle's slot also sees the map.
      epoch.table = table;
      return ((long) epoch.id << 32) | index;
    }
  }

  /**
   * Resolves a handle from the evaluation metadata.
   *
   * @return the prepared features, or {@code null} if the handle is from an older epoch.
   */
  public static Map<String, Object> lookup(final long handle) {
    final Epoch epoch = current;
    if ((int) (handle >>> 32) != epoch.id) {
      return null;
    }
    final int index = (int) handle;
    final Map<String, Object>[] table = epoch.table;
    return index >= 0 && index < table.length ? table[index] : null;
  }

  /**
   * Prepared evaluation features: immutable, with their hash computed once. The flag evaluation
   * aggregator keys its feature sets by map, so the same instance arriving on every evaluation of a
   * split costs it one cached hash and an identity comparison.
   */
  static Map<String, Object> prepared(final Map<String, Object> features) {
    return new Prepared(features);
  }

  private static final class Epoch {
    final int id;
    final ServerConfiguration configuration;
    int size;
    volatile Map<String, Object>[] table;

    @SuppressWarnings("unchecked")
    Epoch(final int id, final ServerConfiguration configuration) {
      this.id = id;
      this.configuration = configuration;
      this.table = new Map[16];
    }
  }

  private static final class Prepared extends AbstractMap<String, Object> {
    private final Map<String, Object> features;
    private final int hash;

    Prepared(final Map<String, Object> features) {
      this.features = Collections.unmodifiableMap(features);
      this.hash = features.hashCode();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
      return features.entrySet();
    }

    @Override
    public Object get(final Object key) {
      return features.get(key);
    }

    @Override
    public boolean containsKey(final Object key) {
      return features.containsKey(key);
    }

    @Override
    public int size() {
      return features.size();
    }

    @Override
    public int hashCode() {
      return hash;
    }

    // AbstractMap.equals checks identity first, so the same instance compares in one step.
  }
}
