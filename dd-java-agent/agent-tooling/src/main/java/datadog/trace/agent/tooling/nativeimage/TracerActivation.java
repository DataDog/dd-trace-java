package datadog.trace.agent.tooling.nativeimage;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.telemetry.TelemetrySystem;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Activates the tracer in native executables, see {@code VMRuntimeInstrumentation}. */
public final class TracerActivation {
  private static final Logger log = LoggerFactory.getLogger(TracerActivation.class);

  public static void activate() {
    SharedCommunicationObjects sco = null;
    try {
      sco = new SharedCommunicationObjects();
    } catch (Throwable e) {
      log.warn("Problem creating shared communication objects", e);
      return;
    }
    try {
      TracerInstaller.installGlobalTracer(sco, ProfilingContextIntegration.NoOp.INSTANCE);
    } catch (Throwable e) {
      log.warn("Problem activating datadog tracer", e);
    }
    if (Config.get().isTelemetryEnabled()) {
      try {
        TelemetrySystem.startTelemetry(null, sco);
      } catch (final Throwable ex) {
        log.warn("Unable start telemetry", ex);
      }
    }
  }
}
