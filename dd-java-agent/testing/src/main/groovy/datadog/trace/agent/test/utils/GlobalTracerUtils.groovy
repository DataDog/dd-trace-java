package datadog.trace.agent.test.utils

import datadog.opentracing.DDTracer
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.CoreTracer

class GlobalTracerUtils {
  // FIXME [OT Split] this shares a lot of logic with TracerInstaller.  Look into combining
  static void registerOrReplaceGlobalTracer(final CoreTracer tracer) {
    final DDTracer tracerOT = new DDTracer(tracer)
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
    return ((DDTracer) io.opentracing.util.GlobalTracer.tracer).coreTracer
  }
}
