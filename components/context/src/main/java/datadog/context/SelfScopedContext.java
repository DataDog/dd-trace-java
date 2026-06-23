package datadog.context;

/** Context that acts as its own unattached scope. */
public interface SelfScopedContext extends Context, ContextScope {
  @Override
  default ContextScope asScope() {
    return this; // acts as no-op scope, avoiding allocation
  }

  @Override
  default Context context() {
    return this;
  }

  @Override
  default void close() {}
}
