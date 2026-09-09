package datadog.context;

/**
 * Context that acts as its own unattached scope.
 */
public interface SelfScopedContext extends Context, ContextScope {
  @Override
  default ContextScope asScope() {
    // acts as no-op scope, avoiding allocation
    return this;
  }

  @Override
  default Context context() {
    return this;
  }

  @Override
  default void close() {}
}
