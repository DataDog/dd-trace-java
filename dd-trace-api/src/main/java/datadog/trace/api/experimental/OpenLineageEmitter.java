package datadog.trace.api.experimental;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.internal.InternalTracer;

public interface OpenLineageEmitter {
  static OpenLineageEmitter get() {
    Tracer tracer = GlobalTracer.get();
    if (tracer instanceof InternalTracer) {
      return ((InternalTracer) tracer).getOpenLineage();
    }

    return OpenLineageEmitter.NoOp.INSTANCE;
  }

  /** @param olEvent OpenLineage event. */
  void emitOpenLineage(String olEvent);

  final class NoOp implements OpenLineageEmitter {

    public static final OpenLineageEmitter INSTANCE = new NoOp();

    @Override
    public void emitOpenLineage(String olEvent) {}
  }
}
