package datadog.trace.api.experimental;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.internal.InternalTracer;

/** This class is experimental and is subject to change and may be removed. */
public interface ProfilingContext {

  static ProfilingContext get() {
    Tracer tracer = GlobalTracer.get();
    if (tracer instanceof InternalTracer) {
      return ((InternalTracer) tracer).getProfilingContext();
    }
    return NoOp.INSTANCE;
  }

  /**
   * Sets a context value to be appended to profiling data
   *
   * @param attribute the attribute (must have been registered at startup)
   * @param value the value
   */
  void setContextValue(String attribute, String value);

  /**
   * Clears a context value
   *
   * @param attribute the attribute (must have been registered at startup)
   */
  void clearContextValue(String attribute);

  <T extends Enum<T>> void setContextValue(T attribute, String value);

  <T extends Enum<T>> void clearContextValue(T attribute);

  final class NoOp implements ProfilingContext {

    public static final NoOp INSTANCE = new NoOp();

    @Override
    public void setContextValue(String attribute, String value) {}

    @Override
    public void clearContextValue(String attribute) {}

    @Override
    public <T extends Enum<T>> void setContextValue(T attribute, String value) {}

    @Override
    public <T extends Enum<T>> void clearContextValue(T attribute) {}
  }
}
