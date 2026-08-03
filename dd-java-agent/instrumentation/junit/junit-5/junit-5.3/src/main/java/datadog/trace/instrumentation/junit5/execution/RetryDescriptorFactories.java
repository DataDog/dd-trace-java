package datadog.trace.instrumentation.junit5.execution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of per-engine {@link RetryDescriptorFactory} keyed by leaf engine id (e.g. {@code
 * spock}, {@code cucumber}). Used by the engine-agnostic retry advice in {@code
 * TestDescriptorHandle}.
 */
public final class RetryDescriptorFactories {

  private static final Map<String, RetryDescriptorFactory> BY_ENGINE_ID = new ConcurrentHashMap<>();

  private RetryDescriptorFactories() {}

  public static void register(String engineId, RetryDescriptorFactory factory) {
    BY_ENGINE_ID.put(engineId, factory);
  }

  public static RetryDescriptorFactory forEngine(String engineId) {
    return engineId != null ? BY_ENGINE_ID.get(engineId) : null;
  }
}
