package datadog.trace.bootstrap;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Map with weakly referenced keys.
 *
 * <p>Keys must not be {@code null}: the backing {@code WeakConcurrentMap} rejects null keys and
 * throws.
 */
public interface WeakMap<K, V> {
  int size();

  boolean containsKey(@Nonnull K target);

  V get(@Nonnull K key);

  void put(@Nonnull K key, V value);

  void putIfAbsent(@Nonnull K key, V value);

  V computeIfAbsent(@Nonnull K key, Function<? super K, ? extends V> supplier);

  V remove(@Nonnull K key);

  abstract class Supplier {
    private static volatile Supplier SUPPLIER;

    protected abstract <K, V> WeakMap<K, V> get();

    public static <K, V> WeakMap<K, V> newWeakMap() {
      return SUPPLIER.get();
    }

    @SuppressFBWarnings(
        value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
        justification = "Agent-internal holder; Class lock does not escape to application code")
    public static synchronized void registerIfAbsent(Supplier supplier) {
      if (null == SUPPLIER) {
        SUPPLIER = supplier;
      }
    }
  }
}
