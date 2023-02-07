package datadog.trace.instrumentation.grpc;

import datadog.trace.api.experimental.ProfilingContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import net.bytebuddy.asm.Advice;

/** TODO replace with generic timing logic in core */
public final class RecordQueueingTimeAdvice {
  @Advice.OnMethodEnter
  public static void beforeSchedule(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
    ProfilingContext context = AgentTracer.get().getProfilingContext();
    // config doesn't make it easy enough to enable an instrumentation only when a flag is set,
    // so we'll check here for now and hope the check gets optimised away by the JIT
    if (context instanceof ProfilingContextIntegration
        && ((ProfilingContextIntegration) context).isQueuingTimeEnabled()
        && !(task instanceof TimedRunnable)) {
      // we know it's safe to wrap here (this will be removed at some point and moved into the
      // scope manager)
      task = new TimedRunnable(task);
    }
  }
}
