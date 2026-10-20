package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.context.ImplicitContextKeyed;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import javax.annotation.Nullable;

/**
 * A {@link Context} that swaps a {@link ScopeStack} back in.
 *
 * <p>Value access, updates and propagation use the underlying context, keeping live stack tokens
 * out of scopes, continuations and carriers.
 */
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

  @Override
  public final Context with(@Nullable ImplicitContextKeyed value) {
    return currentContext().with(value);
  }

  @Override
  public final ContextScope attach() {
    return currentContext().attach();
  }

  @Override
  public final void attachTo(Object carrier) {
    currentContext().attachTo(carrier);
  }

  @Override
  public final ContextContinuation capture() {
    return currentContext().capture();
  }

  @Override
  public final ContextScope asScope() {
    return currentContext().asScope();
  }

  static Context contextOf(@Nullable ContinuableScope scope) {
    return scope != null ? scope.context : Context.root();
  }
}
