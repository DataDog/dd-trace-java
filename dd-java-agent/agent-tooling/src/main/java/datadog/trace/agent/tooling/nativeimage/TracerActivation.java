package datadog.trace.agent.tooling.nativeimage;

import com.datadog.profiling.controller.openjdk.JFREventContextIntegration;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.agent.tooling.ProfilerInstaller;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Activates the tracer in native executables, see {@code VMRuntimeInstrumentation}. */
public final class TracerActivation {
  private static final Logger log = LoggerFactory.getLogger(TracerActivation.class);

  public static void activate() {
    try {
      boolean withProfiler = ProfilerInstaller.installProfiler();
      TracerInstaller.installGlobalTracer(
          new SharedCommunicationObjects(),
          withProfiler
              ? new JFREventContextIntegration()
              : ProfilingContextIntegration.NoOp.INSTANCE);
    } catch (Throwable e) {
      log.warn("Problem activating datadog tracer", e);
    }
  }
}
