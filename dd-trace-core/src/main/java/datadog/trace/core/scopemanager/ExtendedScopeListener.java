package datadog.trace.core.scopemanager;

import datadog.trace.api.DDId;

public interface ExtendedScopeListener {
  void afterScopeActivated(DDId traceId, DDId spanId);

  /** Called just after a scope is closed. */
  void afterScopeClosed();
}
