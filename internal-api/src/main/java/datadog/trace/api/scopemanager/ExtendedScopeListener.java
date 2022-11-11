package datadog.trace.api.scopemanager;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.context.ScopeListener;

public interface ExtendedScopeListener extends ScopeListener {
  void afterScopeActivated(DDTraceId traceId, DDSpanId localRootSpanId, DDSpanId spanId);

  /** Called just after a scope is closed. */
  @Override
  void afterScopeClosed();
}
