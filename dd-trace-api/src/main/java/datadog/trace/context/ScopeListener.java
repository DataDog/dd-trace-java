package datadog.trace.context;

import datadog.trace.api.DDId;

/** Hooks for scope activation */
public interface ScopeListener {
  /**
   * Called just after a scope becomes the active scope
   *
   * <p>May be called multiple times. When a scope is initially created, or after a child scope is
   * deactivated.
   */
  void afterScopeActivated(DDId traceId, DDId spanId);

  /** Called just after a scope is closed. */
  void afterScopeClosed();
}
