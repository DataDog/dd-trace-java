package datadog.trace.agent.tooling;

import com.blogspot.mydailyjava.weaklockfree.WeakConcurrentMap;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.WeakMap;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentTaskScheduler.Task;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class WeakMaps {
  private static final long CLEAN_FREQUENCY_SECONDS = 1;

  public static <K, V> WeakMap<K, V> newWeakMap() {
    final WeakConcurrentMap<K, V> map = new WeakConcurrentMap<>(false, true);
    if (!Platform.isNativeImageBuilder()) {
      AgentTaskScheduler.get()
          .weakScheduleAtFixedRate(
              MapCleaningTask.INSTANCE,
              map,
              CLEAN_FREQUENCY_SECONDS,
              CLEAN_FREQUENCY_SECONDS,
              TimeUnit.SECONDS);
    }
    return new Adapter<>(map);
  }

  private WeakMaps() {}

  public static void registerAsSupplier() {
    WeakMap.Supplier.registerIfAbsent(
        new WeakMap.Supplier() {
          @Override
          protected <K, V> WeakMap<K, V> get() {
            return WeakMaps.newWeakMap();
          }
        });
  }

  // Important to use explicit class to avoid implicit hard references to target
  private static class MapCleaningTask implements Task<WeakConcurrentMap<?, ?>> {
    static final MapCleaningTask INSTANCE = new MapCleaningTask();

    @Override
    public void run(final WeakConcurrentMap<?, ?> target) {
      target.expungeStaleEntries();
    }
  }

  private static class Adapter<K, V> implements WeakMap<K, V> {
    private final WeakConcurrentMap<K, V> map;

    private Adapter(final WeakConcurrentMap<K, V> map) {
      this.map = map;
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
      if (null != value) {
        map.put(key, value);
      } else {
        map.remove(key); // WeakConcurrentMap doesn't accept null values
      }
    }

    @Override
    public void putIfAbsent(final K key, final V value) {
      map.putIfAbsent(key, value);
    }

    @Override
    public V computeIfAbsent(final K key, final Function<? super K, ? extends V> supplier) {
      V value = map.get(key);
      if (null == value) {
        synchronized (this) {
          value = map.get(key);
          if (null == value) {
            value = supplier.apply(key);
            map.put(key, value);
          }
        }
      }
      return value;
    }

    @Override
    public V remove(K key) {
      return map.remove(key);
    }
  }
}
