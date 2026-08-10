package datadog.trace.core;

import datadog.trace.core.scopemanager.ContinuableScopeManager;

/** Bridge to expose package-private {@code CoreTracer.scopeManager} to tests in other packages. */
public class ScopeManagerTestBridge {
  public static ContinuableScopeManager getScopeManager(CoreTracer tracer) {
    return tracer.scopeManager;
  }
}
