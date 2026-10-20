package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import javax.annotation.Nullable;

/** A scope stack owned by a serial execution unit rather than a worker thread. */
final class MovableScopeContext extends ScopeStack implements Context {
  MovableScopeContext(ProfilingContextIntegration profilingContextIntegration) {
    super(profilingContextIntegration);
  }

  private Context current() {
    ContinuableScope active = active();
    return active != null ? active.context : Context.root();
  }

  @Nullable
  @Override
  public <T> T get(ContextKey<T> key) {
    return current().get(key);
  }

  @Override
  public <T> Context with(ContextKey<T> key, @Nullable T value) {
    return current().with(key, value);
  }
}
