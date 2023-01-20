package datadog.trace.api.experimental;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;

/** This class is experimental and is subject to change and may be removed. */
public interface ProfilingContext {

  static ProfilingContext get() {
    Tracer tracer = GlobalTracer.get();
    if (tracer instanceof ProfilingContext) {
      return (ProfilingContext) tracer;
    }
    return (attribute, value) -> {};
  }
  /**
   * Sets a context value to be appended to profiling data
   *
   * @param attribute the attribute (must have been registered at startup)
   * @param value the value
   */
  void setContextValue(String attribute, String value);
}
