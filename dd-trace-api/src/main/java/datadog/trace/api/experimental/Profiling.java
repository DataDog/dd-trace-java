package datadog.trace.api.experimental;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.internal.InternalTracer;

/** This class is experimental and is subject to change and may be removed. */
public interface Profiling extends ProfilingContext {

  static Profiling get() {
    Tracer tracer = GlobalTracer.get();
    if (tracer instanceof InternalTracer) {
      return ((InternalTracer) tracer).getProfilingContext();
    }
    return NoOp.INSTANCE;
  }

  /**
   * Creates a setter for the attribute, slightly more efficient than calling setContextValue
   *
   * @param attribute the name of the attribute
   * @return a setter which can be used to set and clear profiling context
   */
  ProfilingContextSetter createContextSetter(String attribute);

  /**
   * Stateful API which restores the previous context when closed. This requires more memory so has
   * higher overhead than the stateless API.
   *
   * @return a profiling scope which can be closed to restore the current state.
   */
  ProfilingScope newScope();

  final class NoOp implements Profiling {

    public static final NoOp INSTANCE = new NoOp();

    @Override
    public ProfilingContextSetter createContextSetter(String attribute) {
      return ProfilingContextSetter.NoOp.INSTANCE;
    }

    @Override
    public ProfilingScope newScope() {
      return ProfilingScope.NO_OP;
    }
  }
}
