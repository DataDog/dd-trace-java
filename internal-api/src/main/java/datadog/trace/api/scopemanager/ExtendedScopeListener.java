package datadog.trace.api.scopemanager;

import datadog.trace.api.DDTraceId;

public interface ExtendedScopeListener extends ScopeListener {
  void afterScopeActivated(DDTraceId traceId, long spanId);

  /** Called just after a scope is closed. */
  @Override
  void afterScopeClosed();
}
