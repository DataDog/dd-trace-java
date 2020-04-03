package datadog.trace.agent.test.utils

import datadog.opentracing.DDTracerOT
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.DDTracer

class GlobalTracerUtils {
  // FIXME [OT Split] this shares a lot of logic with TracerInstaller.  Look into combining
  static void registerOrReplaceGlobalTracer(final DDTracer tracer) {
    final DDTracerOT tracerOT = new DDTracerOT(tracer)
    try {
      datadog.trace.api.GlobalTracer.registerIfAbsent(tracer)
      AgentTracer.registerIfAbsent(tracer)
      io.opentracing.util.GlobalTracer.register(tracerOT)
    } catch (final Exception e) {
      io.opentracing.util.GlobalTracer.tracer = tracerOT
    }

    if (!io.opentracing.util.GlobalTracer.isRegistered()) {
      throw new RuntimeException("Unable to register the global tracer.")
    }
  }

  /** Get the tracer implementation out of the GlobalTracer */
  static AgentTracer.TracerAPI getUnderlyingGlobalTracer() {
    return ((DDTracerOT) io.opentracing.util.GlobalTracer.tracer).coreTracer
  }
}
