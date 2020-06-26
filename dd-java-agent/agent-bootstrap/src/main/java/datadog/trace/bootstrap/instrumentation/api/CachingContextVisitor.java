package datadog.trace.bootstrap.instrumentation.api;

public abstract class CachingContextVisitor<C> implements AgentPropagation.ContextVisitor<C> {

  private final FixedSizeCache<String, String> cache = new FixedSizeCache<>(32);
  private static final FixedSizeCache.Creator<String, String> LOWER_CASE =
      new FixedSizeCache.LowerCase();

  protected String toLowerCase(String key) {
    return cache.computeIfAbsent(key, LOWER_CASE);
  }
}
