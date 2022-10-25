package datadog.trace.api.scopemanager;

import datadog.trace.api.DDId;
import datadog.trace.context.ScopeListener;

public interface ExtendedScopeListener extends ScopeListener {
  void afterScopeActivated(DDId traceId, DDId localRootSpanId, DDId spanId);

  /** Called just after a scope is closed. */
  @Override
  void afterScopeClosed();
}
