package datadog.trace.agent.tooling.nativeimage;

import com.datadog.profiling.controller.openjdk.JFREventContextIntegration;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.agent.tooling.ProfilerInstaller;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.core.CoreTracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Activates the tracer in native executables, see {@code VMRuntimeInstrumentation}. */
public final class TracerActivation {
  private static final Logger log = LoggerFactory.getLogger(TracerActivation.class);

  public static void activate() {
    try {
      boolean withProfiler = ProfilerInstaller.installProfiler();
      CoreTracer tracer =
          TracerInstaller.installGlobalTracer(
              new SharedCommunicationObjects(),
              withProfiler
                  ? new JFREventContextIntegration()
                  : ProfilingContextIntegration.NoOp.INSTANCE);
      // Need to start the profiling context integration explicitly
      // When run as java agent this is done when the agent itself is started -
      // for native image we need to run it ourselves
      if (tracer != null) {
        tracer.getProfilingContext().onStart();
        log.debug("Profiling context started");
      }
    } catch (Throwable e) {
      log.warn("Problem activating datadog tracer", e);
    }
  }
}
