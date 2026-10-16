package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import javax.annotation.Nullable;

/** A {@link Context} that swaps a {@link ScopeStack} back in. */
abstract class ScopeStackContext implements Context {

  /** Returns the stack to install on the current thread. */
  abstract ScopeStack restore(ProfilingContextIntegration profilingContextIntegration);

  static Context contextOf(@Nullable ContinuableScope scope) {
    return scope != null ? scope.context : Context.root();
  }
}
