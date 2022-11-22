package datadog.telemetry;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.telemetry.dependency.DependencyPeriodicAction;
import datadog.telemetry.dependency.DependencyService;
import datadog.telemetry.dependency.DependencyServiceImpl;
import datadog.telemetry.integration.IntegrationPeriodicAction;
import datadog.trace.api.Config;
import datadog.trace.util.AgentThreadFactory;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetrySystem {

  private static final long TELEMETRY_STOP_WAIT_MILLIS = 5000L;
  private static final Logger log = LoggerFactory.getLogger(TelemetrySystem.class);

  private static Thread TELEMETRY_THREAD;
  private static DependencyService DEPENDENCY_SERVICE;

  static DependencyService createDependencyService(Instrumentation instrumentation) {
    if (instrumentation != null) {
      DependencyServiceImpl dependencyService = new DependencyServiceImpl();
      dependencyService.installOn(instrumentation);
      dependencyService.schedulePeriodicResolution();
      return dependencyService;
    }
    return null;
  }

  static Thread createTelemetryRunnable(
      TelemetryService telemetryService,
      SharedCommunicationObjects sco,
      DependencyService dependencyService) {
    DEPENDENCY_SERVICE = dependencyService;
    TelemetryRunnable telemetryRunnable =
        new TelemetryRunnable(
            new AgentDiscoverer(sco),
            telemetryService,
            Config.get().getTelemetryHeartbeatInterval(),
            Arrays.asList(
                new DependencyPeriodicAction(dependencyService), new IntegrationPeriodicAction()));
    return AgentThreadFactory.newAgentThread(
        AgentThreadFactory.AgentThread.TELEMETRY, telemetryRunnable);
  }

  public static void startTelemetry(
      Instrumentation instrumentation, SharedCommunicationObjects sco) {
    DependencyService dependencyService = createDependencyService(instrumentation);
    TelemetryHttpClient httpClient = new TelemetryHttpClient(sco.okHttpClient);
    TelemetryService telemetryService = new TelemetryServiceImpl(httpClient);
    TELEMETRY_THREAD = createTelemetryRunnable(telemetryService, sco, dependencyService);
    TELEMETRY_THREAD.start();
  }

  public static void stop() {
    if (DEPENDENCY_SERVICE != null) {
      DEPENDENCY_SERVICE.stop();
    }
    if (TELEMETRY_THREAD != null) {
      TELEMETRY_THREAD.interrupt();
      try {
        TELEMETRY_THREAD.join(TELEMETRY_STOP_WAIT_MILLIS);
      } catch (InterruptedException e) {
        log.warn("Telemetry thread join was interrupted");
      }
      if (TELEMETRY_THREAD.isAlive()) {
        log.warn("Telemetry thread join was not completed");
      }
      TELEMETRY_THREAD = null;
    }
  }
}
