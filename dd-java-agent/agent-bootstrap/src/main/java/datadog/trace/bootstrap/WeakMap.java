package datadog.trace.bootstrap;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.function.Function;

public interface WeakMap<K, V> {
  int size();

  boolean containsKey(K target);

  V get(K key);

  void put(K key, V value);

  void putIfAbsent(K key, V value);

  V computeIfAbsent(K key, Function<? super K, ? extends V> supplier);

  V remove(K key);

  abstract class Supplier {
    private static volatile Supplier SUPPLIER;

    protected abstract <K, V> WeakMap<K, V> get();

    public static <K, V> WeakMap<K, V> newWeakMap() {
      return SUPPLIER.get();
    }

    // SpotBugs USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION: false positive, can be suppressed.
    // WeakMap.Supplier is an agent-internal holder loaded in the isolated agent context; its Class
    // lock does not escape to application code, and the lock only guards the private SUPPLIER
    // field.
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
