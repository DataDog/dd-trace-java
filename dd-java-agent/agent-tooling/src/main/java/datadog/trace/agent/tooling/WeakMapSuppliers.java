package datadog.trace.agent.tooling;

import com.blogspot.mydailyjava.weaklockfree.WeakConcurrentMap;
import com.google.common.annotations.VisibleForTesting;
import datadog.common.exec.AgentTaskScheduler;
import datadog.common.exec.AgentTaskScheduler.Task;
import datadog.trace.bootstrap.WeakMap;
import java.util.concurrent.TimeUnit;

class WeakMapSuppliers {
  // Comparison with using WeakConcurrentMap vs Guava's implementation:
  // Cleaning:
  // * `WeakConcurrentMap`: centralized but we have to maintain out own code and thread for it
  // * `Guava`: inline on application's thread, with constant max delay
  // Jar Size:
  // * `WeakConcurrentMap`: small
  // * `Guava`: large, but we may use other features, like immutable collections - and we already
  //          ship Guava as part of distribution now, so using Guava for this doesn’t increase size.
  // Must go on bootstrap classpath:
  // * `WeakConcurrentMap`: version conflict is unlikely, so we can directly inject for now
  // * `Guava`: need to implement shadow copy (might eventually be necessary for other dependencies)
  // Used by other javaagents for similar purposes:
  // * `WeakConcurrentMap`: anecdotally used by other agents
  // * `Guava`: specifically agent use is unknown at the moment, but Guava is a well known library
  //            backed by big company with many-many users

  /**
   * Provides instances of {@link WeakConcurrentMap} and retains weak reference to them to allow a
   * single thread to clean void weak references out for all instances. Cleaning is done every
   * second.
   */
  static class WeakConcurrent implements WeakMap.Implementation {

    @VisibleForTesting static final long CLEAN_FREQUENCY_SECONDS = 1;

    @Override
    public <K, V> WeakMap<K, V> get() {
      final WeakConcurrentMap<K, V> map = new WeakConcurrentMap<>(false, true);
      AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
          MapCleaningTask.INSTANCE,
          map,
          CLEAN_FREQUENCY_SECONDS,
          CLEAN_FREQUENCY_SECONDS,
          TimeUnit.SECONDS);
      return new Adapter<>(map);
    }

    // Important to use explicit class to avoid implicit hard references to target
    private static class MapCleaningTask implements Task<WeakConcurrentMap> {

      static final MapCleaningTask INSTANCE = new MapCleaningTask();

      @Override
      public void run(final WeakConcurrentMap target) {
        target.expungeStaleEntries();
      }
    }

    private static class Adapter<K, V> implements WeakMap<K, V> {
      private final Object[] locks = new Object[16];
      private final WeakConcurrentMap<K, V> map;

      private Adapter(final WeakConcurrentMap<K, V> map) {
        this.map = map;
        for (int i = 0; i < locks.length; ++i) {
          locks[i] = new Object();
        }
      }

      @Override
      public int size() {
        return map.approximateSize();
      }

      @Override
      public boolean containsKey(final K key) {
        return map.containsKey(key);
      }

      @Override
      public V get(final K key) {
        return map.get(key);
      }

      @Override
      public void put(final K key, final V value) {
        map.put(key, value);
      }

      @Override
      public void putIfAbsent(final K key, final V value) {
        map.putIfAbsent(key, value);
      }

      @Override
      public V computeIfAbsent(final K key, final ValueSupplier<? super K, ? extends V> supplier) {
        V value = map.get(key);
        if (null == value) {
          synchronized (locks[key.hashCode() & (locks.length - 1)]) {
            value = map.get(key);
            if (null == value) {
              value = supplier.get(key);
              map.put(key, value);
            }
          }
        }
        return value;
      }
    }

    static class Inline implements WeakMap.Implementation {

      @Override
      public <K, V> WeakMap<K, V> get() {
        return new Adapter<>(new WeakConcurrentMap.WithInlinedExpunction<K, V>());
      }
    }
  }
}
