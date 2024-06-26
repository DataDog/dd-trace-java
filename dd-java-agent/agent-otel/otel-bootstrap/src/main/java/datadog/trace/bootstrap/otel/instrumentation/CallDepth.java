package datadog.trace.bootstrap.otel.instrumentation;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;

/** Redirects requests to our own {@link CallDepthThreadLocalMap}. */
public final class CallDepth {
  private final Class<?> clazz;

  private CallDepth(final Class<?> clazz) {
    this.clazz = clazz;
  }

  public static CallDepth forClass(Class<?> clazz) {
    return new CallDepth(clazz);
  }

  public int getAndIncrement() {
    return CallDepthThreadLocalMap.incrementCallDepth(clazz);
  }

  public int decrementAndGet() {
    return CallDepthThreadLocalMap.decrementCallDepth(clazz);
  }
}
