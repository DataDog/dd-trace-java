package datadog.trace.api.iast;

import datadog.trace.api.iast.telemetry.Verbosity;

/**
 * Interface used to mark advice implementations using @CallSite so they are instrumented by
 * IastInstrumentation
 */
public interface IastCallSites {

  interface HasTelemetry {
    void setVerbosity(Verbosity verbosity);
  }
}
