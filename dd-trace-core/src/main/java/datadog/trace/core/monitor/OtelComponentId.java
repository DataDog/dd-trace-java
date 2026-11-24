package datadog.trace.core.monitor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Identity of one pipeline component instance: {@code otel.component.type} plus {@code
 * otel.component.name} = {@code <type>/<n>}, where {@code n} counts instances of that type from 0.
 * Create one per component instance and keep it.
 */
public final class OtelComponentId {
  private static final ConcurrentMap<String, AtomicInteger> SEQUENCES = new ConcurrentHashMap<>();

  private final String type;
  private final String name;

  private OtelComponentId(String type, int sequence) {
    this.type = type;
    this.name = type + '/' + sequence;
  }

  /** Returns a new identity with the next sequence number for the given component type. */
  public static OtelComponentId next(String type) {
    return new OtelComponentId(
        type, SEQUENCES.computeIfAbsent(type, t -> new AtomicInteger()).getAndIncrement());
  }

  public String type() {
    return type;
  }

  public String name() {
    return name;
  }

  @Override
  public String toString() {
    return name;
  }
}
