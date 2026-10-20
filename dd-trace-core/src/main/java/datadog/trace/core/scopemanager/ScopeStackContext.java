package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import javax.annotation.Nullable;

/** A {@link Context} that swaps a {@link ScopeStack} back in. */
abstract class ScopeStackContext implements Context {

  /** Returns the stack to install on the current thread. */
  abstract ScopeStack restore(ProfilingContextIntegration profilingContextIntegration);

  /** The context that lookups and updates on this token are forwarded to. */
  abstract Context currentContext();

  @Nullable
  @Override
  public final <T> T get(ContextKey<T> key) {
    return currentContext().get(key);
  }

  @Override
  public final <T> Context with(ContextKey<T> key, @Nullable T value) {
    return currentContext().with(key, value);
  }

  static Context contextOf(@Nullable ContinuableScope scope) {
    return scope != null ? scope.context : Context.root();
  }
}
