package datadog.telemetry;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.telemetry.TelemetryRunnable.TelemetryPeriodicAction;
import datadog.telemetry.dependency.DependencyPeriodicAction;
import datadog.telemetry.dependency.DependencyService;
import datadog.telemetry.integration.IntegrationPeriodicAction;
import datadog.telemetry.metric.CoreMetricsPeriodicAction;
import datadog.telemetry.metric.IastMetricPeriodicAction;
import datadog.telemetry.metric.WafMetricPeriodicAction;
import datadog.trace.api.Config;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.util.AgentThreadFactory;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelemetrySystem {

  private static final long TELEMETRY_STOP_WAIT_MILLIS = 5000L;
  private static final Logger log = LoggerFactory.getLogger(TelemetrySystem.class);

  private static Thread TELEMETRY_THREAD;
  private static DependencyService DEPENDENCY_SERVICE;

  static DependencyService createDependencyService(Instrumentation instrumentation) {
    if (instrumentation != null && Config.get().isTelemetryDependencyServiceEnabled()) {
      DependencyService dependencyService = new DependencyService();
      dependencyService.installOn(instrumentation);
      dependencyService.schedulePeriodicResolution();
      return dependencyService;
    }
    return null;
  }

  static Thread createTelemetryRunnable(
      TelemetryService telemetryService, DependencyService dependencyService) {
    DEPENDENCY_SERVICE = dependencyService;

    List<TelemetryPeriodicAction> actions = new ArrayList<>();
    actions.add(new CoreMetricsPeriodicAction());
    actions.add(new IntegrationPeriodicAction());
    actions.add(new WafMetricPeriodicAction());
    if (Verbosity.OFF != Config.get().getIastTelemetryVerbosity()) {
      actions.add(new IastMetricPeriodicAction());
    }
    if (null != dependencyService) {
      actions.add(new DependencyPeriodicAction(dependencyService));
    }

    TelemetryRunnable telemetryRunnable = new TelemetryRunnable(telemetryService, actions);
    return AgentThreadFactory.newAgentThread(
        AgentThreadFactory.AgentThread.TELEMETRY, telemetryRunnable);
  }

  /** Called by reflection (see Agent.startTelemetry) */
  public static void startTelemetry(
      Instrumentation instrumentation, SharedCommunicationObjects sco) {
    sco.createRemaining(Config.get());
    DependencyService dependencyService = createDependencyService(instrumentation);
    TelemetryService telemetryService = new TelemetryService(sco.okHttpClient, sco.agentUrl);
    TELEMETRY_THREAD = createTelemetryRunnable(telemetryService, dependencyService);
    TELEMETRY_THREAD.start();
  }

  public static void stop() {
    // TODO This is never called in the prod code. Should be part of Agent.shutdown?
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
